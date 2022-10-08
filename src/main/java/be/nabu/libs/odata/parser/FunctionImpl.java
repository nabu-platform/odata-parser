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
