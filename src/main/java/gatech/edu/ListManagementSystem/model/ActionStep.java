package gatech.edu.ListManagementSystem.model;

import java.util.List;
import java.util.Map;

import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.ElementCollection;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.ManyToMany;
import javax.persistence.MapKeyColumn;
import javax.persistence.Table;

@Entity
@Table(name = "actionStep", schema = "listmanagementsystem")
public class ActionStep implements Runnable{
	@Id
	@Column(name = "id")
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	protected Integer id;
	@Column(name = "name")
	protected String name;
	@Column(name = "type")
	protected ActionStepType type;
	@ElementCollection
	protected Map<String,String> params;
	
	@Override
	public void run() {
		// TODO Auto-generated method stub
		
	}
}
