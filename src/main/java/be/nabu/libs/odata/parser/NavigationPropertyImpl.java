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

import be.nabu.libs.odata.types.NavigationProperty;
import be.nabu.libs.types.api.Element;
import be.nabu.libs.types.structure.Structure;

public class NavigationPropertyImpl implements NavigationProperty {

	private String qualifiedName;
	private Element<?> element;
	private Structure updateType;
	private boolean containsTarget;
	private org.w3c.dom.Element domElement;
	
	@Override
	public String getQualifiedName() {
		return qualifiedName;
	}
	public void setQualifiedName(String qualifiedName) {
		this.qualifiedName = qualifiedName;
	}
	@Override
	public Element<?> getElement() {
		return element;
	}
	public void setElement(Element<?> element) {
		this.element = element;
	}
	
	@Override
	public Structure getUpdateType() {
		return updateType;
	}
	public void setUpdateType(Structure updateType) {
		this.updateType = updateType;
	}
	
	@Override
	public boolean isContainsTarget() {
		return containsTarget;
	}
	public void setContainsTarget(boolean containsTarget) {
		this.containsTarget = containsTarget;
	}
	
	public org.w3c.dom.Element getDomElement() {
		return domElement;
	}
	public void setDomElement(org.w3c.dom.Element domElement) {
		this.domElement = domElement;
	}
	
}
