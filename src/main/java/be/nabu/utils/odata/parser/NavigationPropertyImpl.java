package be.nabu.utils.odata.parser;

import be.nabu.libs.types.api.Element;
import be.nabu.utils.odata.types.NavigationProperty;

public class NavigationPropertyImpl implements NavigationProperty {

	private String qualifiedName;
	private Element<?> element;
	
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

}
