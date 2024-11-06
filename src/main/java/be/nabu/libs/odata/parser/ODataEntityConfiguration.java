/*
* Copyright (C) 2022 Alexander Verbruggen
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Lesser General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Lesser General Public License for more details.
*
* You should have received a copy of the GNU Lesser General Public License
* along with this program. If not, see <https://www.gnu.org/licenses/>.
*/

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
