package gatech.edu.JobManagementSystem.controller;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.PostConstruct;
import javax.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import gatech.edu.JobManagementSystem.JobRunnerService;
import gatech.edu.JobManagementSystem.model.Action;
import gatech.edu.JobManagementSystem.model.ActionType;
import gatech.edu.JobManagementSystem.model.JobState;
import gatech.edu.JobManagementSystem.model.JsonTest;
import gatech.edu.JobManagementSystem.model.ListRunType;
import gatech.edu.JobManagementSystem.model.ListType;
import gatech.edu.JobManagementSystem.model.Person;
import gatech.edu.JobManagementSystem.model.PersonList;
import gatech.edu.JobManagementSystem.model.ProcessImpl.RestAction;
import gatech.edu.JobManagementSystem.repo.ActionRepository;
import gatech.edu.JobManagementSystem.repo.PersonListRepository;
import gatech.edu.JobManagementSystem.repo.JsonTestRepository;
import gatech.edu.JobManagementSystem.repo.JobStateRepository;
import gatech.edu.JobManagementSystem.util.JMSUtil;

@CrossOrigin
@RestController
public class JobManagementController {
	
	private static final Logger log = LoggerFactory.getLogger(JobManagementController.class);
	private PersonListRepository personListRepository;
	private ActionRepository actionRepository;
	private JsonTestRepository jsonTestRepository;
	private JobStateRepository jobStateRepository;
	private TaskScheduler taskScheduler;
	private ObjectMapper objectMapper;
	private JobRunnerService jobRunnerService;
	
	@Autowired
	public JobManagementController(PersonListRepository personListRepository, ActionRepository actionRepository, 
			TaskScheduler taskScheduler, JsonTestRepository jsonTestRepository, JobStateRepository jobStateRepository, 
			JobRunnerService jobRunnerService) {
		this.personListRepository = personListRepository;
		this.actionRepository = actionRepository;
		this.jsonTestRepository = jsonTestRepository;
		this.jobStateRepository = jobStateRepository;
		this.taskScheduler = taskScheduler;
		this.jobRunnerService = jobRunnerService;
		objectMapper = new ObjectMapper();
	}

	
	@RequestMapping(value = "List", method = RequestMethod.POST)
	@Transactional
	public ResponseEntity<JsonNode> postPersonList(@RequestBody PersonList list,HttpServletRequest request){
		log.debug("personlist before presistenceprep:"+list.toString());
		JMSUtil.perparePersonListForPersistence(list);
		log.debug("personlist after presistenceprep:"+list.toString());
		personListRepository.save(list);
		Action action = list.getAction();
		if(action.getActionType() == ActionType.REST) {
			action = new RestAction(action);
		}
		log.debug("action:"+action.toString());
		if(action != null) {
			if(action.getCronString() != null && list.getListType() != ListType.SINGLE_USE) {
				log.debug("scheduling action:"+action.toString());
				taskScheduler.schedule(action, new CronTrigger(action.getCronString()));
			}
			action.run();
		}
		ArrayNode results = JsonNodeFactory.instance.arrayNode();
		for(Person person:list.getListElements()) {
			if(person.getResult() != null) {
				JsonNode node = null;
				try {
					node = objectMapper.readTree(person.getResult());
				} catch (JsonProcessingException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				if(node != null) {
					results.add(node);
				}
			}
		}
		HttpHeaders responseHeaders = new HttpHeaders();
		try {
			responseHeaders.setLocation(new URI("/List/"+list.getName()));
		} catch (URISyntaxException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return new ResponseEntity<JsonNode>(results,responseHeaders,HttpStatus.CREATED);
	}
	
	@RequestMapping(value = "List/{name}", method = RequestMethod.GET)
	public ResponseEntity<PersonList> getECR(@PathVariable("name") String name){
		PersonList personList = personListRepository.findByName(name);
		return new ResponseEntity<PersonList>(personList,HttpStatus.OK);
	}
	
	@RequestMapping(value = "Action", method = RequestMethod.POST)
	public ResponseEntity<Action> postProcess(@RequestBody Action action){
		actionRepository.save(action);
		//TODO: Use TaskManager object to schedule process
		return new ResponseEntity<Action>(action,HttpStatus.CREATED);
	}
	
	// function gets data from specified url and displays it at the given endpoint
	@RequestMapping(value = "test", method = RequestMethod.GET)
	@ResponseBody
	public ResponseEntity<JobState> getJobState() {
		RestTemplate restTemplate = new RestTemplate();
		String uri = "https://60914d9350c2550017677f33.mockapi.io/jobs/1";
		JobState response = restTemplate.getForObject(uri, JobState.class);
		return new ResponseEntity<JobState>(response, HttpStatus.OK);
	}
	
	// Currently working on a two parter:
	// Part 1: POST a new job to the db with jobState of running, run JobRunnerService and return location
	@RequestMapping(value = "Jobs", method = RequestMethod.POST)
	@Transactional
	public ResponseEntity<String> postJob(@RequestBody JobState job, HttpServletRequest request){
		String timeStarted = JMSUtil.formatDate(System.currentTimeMillis());
		job.setTimeStarted(timeStarted);
		job.setJobState("Not Started");
		jobStateRepository.save(job);
		String result = "Job Run Unsuccessful";
		HttpHeaders responseHeaders = new HttpHeaders();
		try {
			responseHeaders.setLocation(new URI("/Jobs/"+job.getJobId()));
			result = "Job results can be found at /Jobs/"+job.getJobId();
			jobRunnerService.runJob(job);
		} catch (URISyntaxException e) {
			e.printStackTrace();
		} catch (InterruptedException ie) {
			ie.printStackTrace();
		}
		return new ResponseEntity<String>(result, responseHeaders, HttpStatus.CREATED);
	}
	
	// Part 2: Retrieve that job state and display it at the new location
	@RequestMapping(value = "Jobs/{jobid}", method = RequestMethod.GET)
	public ResponseEntity<JobState> getJob(@PathVariable("jobid") Integer jobId){
		JobState job = jobStateRepository.findByJobId(jobId);
		return new ResponseEntity<JobState>(job, HttpStatus.OK);
	}
	
	
	//TODO: merge lists together.
	
	public PersonList mergeLists(PersonList listA, PersonList listB) {
		if(listA.getAction() == null) {
			listA.setAction(listB.getAction());
		}
		if(listA.getJobType() == null) {
			listA.setJobType(listB.getJobType());
		}
		if(listA.getListType() == null) {
			listA.setListType(listB.getListType());
		}
		if(listA.getRunType() == null) {
			listA.setRunType(listB.getRunType());
		}
		listA.getListElements().addAll(listB.getListElements());
		return listA;
	}
}