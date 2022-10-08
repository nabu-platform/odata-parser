package be.nabu.libs.odata.parser;

import be.nabu.libs.property.api.Value;
import be.nabu.libs.types.api.Type;
import be.nabu.libs.types.api.Unmarshallable;
import be.nabu.libs.types.base.BaseMarshallableSimpleType;

public class EnumType extends BaseMarshallableSimpleType<java.lang.String> implements Unmarshallable<java.lang.String> {

	private String name, namespace;

	public EnumType(String namespace, String name) {
		super(String.class);
		this.namespace = namespace;
		this.name = name;
	}

	@Override
	public String getName(Value<?>... values) {
		return name;
	}

	@Override
	public String getNamespace(Value<?>... values) {
		return namespace;
	}

	@Override
	public String marshal(String object, Value<?>... values) {
		return object;
	}

	@Override
	public String unmarshal(String content, Value<?>... values) {
		return content;
	}

	@Override
	public Type getSuperType() {
		return new be.nabu.libs.types.simple.String();
	}
	
}
