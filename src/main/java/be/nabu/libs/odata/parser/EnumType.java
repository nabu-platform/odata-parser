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
