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

import be.nabu.libs.odata.types.Function;
import be.nabu.libs.types.api.ComplexType;

public class FunctionImpl implements Function {
	private boolean action;
	private ComplexType input, output;
	private String context, name, method;
	@Override
	public boolean isAction() {
		return action;
	}
	public void setAction(boolean action) {
		this.action = action;
	}
	@Override
	public ComplexType getInput() {
		return input;
	}
	public void setInput(ComplexType input) {
		this.input = input;
	}
	@Override
	public ComplexType getOutput() {
		return output;
	}
	public void setOutput(ComplexType output) {
		this.output = output;
	}
	@Override
	public String getContext() {
		return context;
	}
	public void setContext(String context) {
		this.context = context;
	}
	@Override
	public String getName() {
		return name;
	}
	public void setName(String name) {
		this.name = name;
	}
	@Override
	public String getMethod() {
		return method;
	}
	public void setMethod(String method) {
		this.method = method;
	}
}
