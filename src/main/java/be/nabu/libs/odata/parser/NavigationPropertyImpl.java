package be.nabu.libs.odata.parser;

import be.nabu.libs.odata.types.NavigationProperty;
import be.nabu.libs.types.api.Element;
import be.nabu.libs.types.structure.Structure;

public class NavigationPropertyImpl implements NavigationProperty {

	private String qualifiedName;
	private Element<?> element;
	private Structure updateType;
	
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

}
