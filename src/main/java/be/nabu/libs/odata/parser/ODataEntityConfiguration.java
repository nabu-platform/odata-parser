package be.nabu.libs.odata.parser;

import java.util.List;

// you can request to expand certain bound objects, they will be added to the core object at that point and requested explicitly to be expanded
// by default we expand nothing
public class ODataEntityConfiguration {
	private String entity;
	private List<String> expansions;
	// you can have data bindings in odata, however it is unclear if the PATCH is additive or not when it comes to 1-* relations
	// when you add a mananagedbinding, we generate a service specifically aimed at managing this particular binding field and making sure the bindings are always complete
	private List<String> managedBindings;
	public String getEntity() {
		return entity;
	}
	public void setEntity(String entity) {
		this.entity = entity;
	}
	public List<String> getExpansions() {
		return expansions;
	}
	public void setExpansions(List<String> expansions) {
		this.expansions = expansions;
	}
	public List<String> getManagedBindings() {
		return managedBindings;
	}
	public void setManagedBindings(List<String> managedBindings) {
		this.managedBindings = managedBindings;
	}
	
}
