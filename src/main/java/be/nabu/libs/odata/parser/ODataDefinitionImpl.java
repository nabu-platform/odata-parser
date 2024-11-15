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

import be.nabu.libs.odata.ODataDefinition;
import be.nabu.libs.odata.types.ConformanceLevel;
import be.nabu.libs.odata.types.Function;
import be.nabu.libs.odata.types.NavigationProperty;
import be.nabu.libs.types.api.TypeRegistry;

public class ODataDefinitionImpl implements ODataDefinition {

	private String version, scheme, host, basePath;
	private List<String> supportedFormats, filterFunctions;
	private ConformanceLevel conformanceLevel;
	private Boolean asynchronousRequestsSupported, batchContinueOnErrorSupported, crossJoinSupported;
	private TypeRegistry registry;
	private List<Function> functions;
	private List<NavigationProperty> navigationProperties;
	
	@Override
	public String getVersion() {
		return version;
	}
	public void setVersion(String version) {
		this.version = version;
	}
	@Override
	public String getScheme() {
		return scheme;
	}
	public void setScheme(String scheme) {
		this.scheme = scheme;
	}
	@Override
	public String getHost() {
		return host;
	}
	public void setHost(String host) {
		this.host = host;
	}
	@Override
	public String getBasePath() {
		return basePath;
	}
	public void setBasePath(String basePath) {
		this.basePath = basePath;
	}
	@Override
	public List<String> getSupportedFormats() {
		return supportedFormats;
	}
	public void setSupportedFormats(List<String> supportedFormats) {
		this.supportedFormats = supportedFormats;
	}
	@Override
	public List<String> getFilterFunctions() {
		return filterFunctions;
	}
	public void setFilterFunctions(List<String> filterFunctions) {
		this.filterFunctions = filterFunctions;
	}
	@Override
	public ConformanceLevel getConformanceLevel() {
		return conformanceLevel;
	}
	public void setConformanceLevel(ConformanceLevel conformanceLevel) {
		this.conformanceLevel = conformanceLevel;
	}
	@Override
	public Boolean getAsynchronousRequestsSupported() {
		return asynchronousRequestsSupported;
	}
	public void setAsynchronousRequestsSupported(Boolean asynchronousRequestsSupported) {
		this.asynchronousRequestsSupported = asynchronousRequestsSupported;
	}
	@Override
	public Boolean getBatchContinueOnErrorSupported() {
		return batchContinueOnErrorSupported;
	}
	public void setBatchContinueOnErrorSupported(Boolean batchContinueOnErrorSupported) {
		this.batchContinueOnErrorSupported = batchContinueOnErrorSupported;
	}
	@Override
	public Boolean getCrossJoinSupported() {
		return crossJoinSupported;
	}
	public void setCrossJoinSupported(Boolean crossJoinSupported) {
		this.crossJoinSupported = crossJoinSupported;
	}
	@Override
	public TypeRegistry getRegistry() {
		return registry;
	}
	public void setRegistry(TypeRegistry registry) {
		this.registry = registry;
	}
	@Override
	public List<Function> getFunctions() {
		return functions;
	}
	public void setFunctions(List<Function> functions) {
		this.functions = functions;
	}
	@Override
	public List<NavigationProperty> getNavigationProperties() {
		return navigationProperties;
	}
	public void setNavigationProperties(List<NavigationProperty> navigationProperties) {
		this.navigationProperties = navigationProperties;
	}

}
