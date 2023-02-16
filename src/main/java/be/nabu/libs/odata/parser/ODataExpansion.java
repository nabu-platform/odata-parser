package be.nabu.libs.odata.parser;

import java.util.List;

// you can request to expand certain bound objects, they will be added to the core object at that point and requested explicitly to be expanded
// by default we expand nothing
public class ODataExpansion {
	private String entity;
	private List<String> expansions;
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
}
