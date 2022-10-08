package be.nabu.libs.odata.parser;

import java.io.InputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import be.nabu.libs.evaluator.PathAnalyzer;
import be.nabu.libs.evaluator.QueryParser;
import be.nabu.libs.evaluator.api.Operation;
import be.nabu.libs.evaluator.impl.PlainOperationProvider;
import be.nabu.libs.events.impl.EventDispatcherImpl;
import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.api.client.HTTPClient;
import be.nabu.libs.http.client.nio.NIOHTTPClientImpl;
import be.nabu.libs.http.core.CustomCookieStore;
import be.nabu.libs.http.core.DefaultHTTPRequest;
import be.nabu.libs.http.server.nio.MemoryMessageDataProvider;
import be.nabu.libs.odata.ODataDefinition;
import be.nabu.libs.odata.types.Function;
import be.nabu.libs.odata.types.NavigationProperty;
import be.nabu.libs.property.ValueUtils;
import be.nabu.libs.property.api.Value;
import be.nabu.libs.resources.URIUtils;
import be.nabu.libs.types.SimpleTypeWrapperFactory;
import be.nabu.libs.types.TypeRegistryImpl;
import be.nabu.libs.types.TypeUtils;
import be.nabu.libs.types.api.ComplexType;
import be.nabu.libs.types.api.SimpleType;
import be.nabu.libs.types.api.Type;
import be.nabu.libs.types.base.ComplexElementImpl;
import be.nabu.libs.types.base.Duration;
import be.nabu.libs.types.base.SimpleElementImpl;
import be.nabu.libs.types.base.TypeBaseUtils;
import be.nabu.libs.types.base.ValueImpl;
import be.nabu.libs.types.java.BeanResolver;
import be.nabu.libs.types.properties.AliasProperty;
import be.nabu.libs.types.properties.CollectionNameProperty;
import be.nabu.libs.types.properties.EnumerationProperty;
import be.nabu.libs.types.properties.MaxOccursProperty;
import be.nabu.libs.types.properties.MinOccursProperty;
import be.nabu.libs.types.properties.NameProperty;
import be.nabu.libs.types.properties.NillableProperty;
import be.nabu.libs.types.properties.PrimaryKeyProperty;
import be.nabu.libs.types.properties.RestrictProperty;
import be.nabu.libs.types.structure.DefinedStructure;
import be.nabu.libs.types.structure.Structure;
import be.nabu.libs.types.xml.XMLContent;
import be.nabu.utils.io.IOUtils;
import be.nabu.utils.io.api.ByteBuffer;
import be.nabu.utils.io.api.ReadableContainer;
import be.nabu.utils.mime.api.ContentPart;
import be.nabu.utils.mime.impl.MimeHeader;
import be.nabu.utils.mime.impl.PlainMimeEmptyPart;
import be.nabu.utils.xml.BaseNamespaceResolver;
import be.nabu.utils.xml.XMLUtils;
import be.nabu.utils.xml.XPath;

/**
 * The parser initially used XPath in a number of places.
 * However, when loading the odata definition file for navision (which is 8.5mb without pretty print). The file is so massive that visual studio code can not prettify it on an i9 laptop with 32gb ram.
 * The parser initially took 1.5 min to parse the entire file into structures etc on an i9 laptop.
 * A cursory glance at jvisualvm confirmed that 98% of the overhead was xpath. 
 * So i added an alternative implementation where all the queries are performed with the blox/glue engine. The parser then took 1.2s.
 * I did not dig too deep into the differences, the evaluation engine has historically been faster than the standard xpath but i think part of the overhead might be constructing the queries because of the massive amount of times the queries have to be run.
 * I did not test this but currently the xpath queries are constructing every time while the queryparser inherently caches parsed queries.
 */

public class ODataParser {
	
	private HTTPClient httpClient;
	
	private static final String NS_EDM = "http://docs.oasis-open.org/odata/ns/edm";
	private static final String NS_EDMX = "http://docs.oasis-open.org/odata/ns/edmx";

	private Logger logger = LoggerFactory.getLogger(getClass());
	private String baseId;
	
	public static void main(String...args) throws URISyntaxException, ParseException {
		ODataDefinition definition = new ODataParser().parse(new URI("https://services.odata.org/V4/TripPinService"));
		System.out.println("Endpoint: " + definition.getScheme() + "://" + definition.getHost() + definition.getBasePath());
		System.out.println("Version: " + definition.getVersion());
	}
	
	// you can create aliases to namespaces as shorthands (basically a lot like prefixes...)
	private Map<String, String> aliases = new HashMap<String, String>();

	private PathAnalyzer<Object> pathAnalyzer;
	
	public ODataDefinition parse(URI url) throws ParseException {
		try (InputStream metadata = getMetadata(url)) {
			return parse(url, metadata);
		}
		catch (ParseException e) {
			throw e;
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}		
	}
	
	public ODataDefinition parse(URI url, InputStream input) throws ParseException {
		ODataDefinitionImpl definition = new ODataDefinitionImpl();
		definition.setScheme(url.getScheme());
		definition.setHost(url.getHost());
		definition.setBasePath(url.getPath());
		definition.setRegistry(new TypeRegistryImpl());
		definition.setFunctions(new ArrayList<Function>());
		definition.setNavigationProperties(new ArrayList<NavigationProperty>());
		parse(url, input, definition);
		return definition;
	}
	
	public HTTPClient getHTTPClient() {
		if (httpClient == null) {
			try {
				httpClient = new NIOHTTPClientImpl(SSLContext.getDefault(), 5, 5, 10, new EventDispatcherImpl(), new MemoryMessageDataProvider(), new CookieManager(new CustomCookieStore(), CookiePolicy.ACCEPT_NONE), Executors.defaultThreadFactory());
			}
			catch (Exception e) {
				throw new RuntimeException(e);
			}
		}
		return httpClient;
	}
	public void setHttpClient(HTTPClient httpClient) {
		this.httpClient = httpClient;
	}
	
	public InputStream getMetadata(URI url) {
		URI child = URIUtils.getChild(url, "$metadata");
		HTTPClient httpClient = getHTTPClient();
		HTTPRequest request = new DefaultHTTPRequest("GET", child.getPath(), new PlainMimeEmptyPart(null, 
			new MimeHeader("Content-Length", "0"),
			new MimeHeader("Accept", "application/xml"),
			new MimeHeader("User-Agent", "User agent"),
			new MimeHeader("Host", child.getHost())
		));
		try {
			HTTPResponse response = httpClient.execute(request, null, child.getScheme().equals("https"), true);
			if (response.getCode() >= 200 && response.getCode() < 300) {
				if (response.getContent() instanceof ContentPart) {
					ReadableContainer<ByteBuffer> readable = ((ContentPart) response.getContent()).getReadable();
					return IOUtils.toInputStream(readable);
				}
			}
		}
		catch (RuntimeException e) {
			throw e;
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
		return null;
	}

	private void parse(URI url, InputStream metadata, ODataDefinitionImpl definition) throws ParseException {
		try {
			Document document = XMLUtils.toDocument(metadata, true);
			if (!document.getDocumentElement().getLocalName().equals("Edmx")) {
				throw new ParseException("Not an edmx document, the root tag is: " + document.getDocumentElement().getTagName(), 0);
			}
			definition.setVersion(document.getDocumentElement().getAttribute("Version"));
			parseRoot(document.getDocumentElement(), definition);
		}
		catch (ParseException e) {
			throw e;
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
	
	private void parseRoot(Element element, ODataDefinitionImpl definition) {
		NodeList children = element.getElementsByTagNameNS(NS_EDMX, "DataServices");
		for (int i = 0; i < children.getLength(); i++) {
			parseDataServices((Element) children.item(i), definition);
		}
	}
	
	private void parseDataServices(Element element, ODataDefinitionImpl definition) {
		NodeList children = element.getElementsByTagNameNS(NS_EDM, "Schema");
		for (int i = 0; i < children.getLength(); i++) {
			parseSchema((Element) children.item(i), definition);
		}
	}
	
	private void parseSchema(Element element, ODataDefinitionImpl definition) {
		String namespace = element.getAttribute("Namespace");
		String alias = element.getAttribute("Alias");
		if (alias != null && !alias.trim().isEmpty()) {
			aliases.put(alias, namespace);
		}
		NodeList children = element.getElementsByTagNameNS(NS_EDM, "EnumType");
		for (int i = 0; i < children.getLength(); i++) {
			parseEnumType((Element) children.item(i), definition, namespace);
		}
		// preparsing
		children = element.getElementsByTagNameNS(NS_EDM, "ComplexType");
		for (int i = 0; i < children.getLength(); i++) {
			preparseComplexType((Element) children.item(i), definition, namespace);
		}
		children = element.getElementsByTagNameNS(NS_EDM, "EntityType");
		for (int i = 0; i < children.getLength(); i++) {
			preparseComplexType((Element) children.item(i), definition, namespace);
		}
		// actual parsing
		children = element.getElementsByTagNameNS(NS_EDM, "ComplexType");
		for (int i = 0; i < children.getLength(); i++) {
			parseComplexType((Element) children.item(i), definition, namespace);
		}
		children = element.getElementsByTagNameNS(NS_EDM, "EntityType");
		for (int i = 0; i < children.getLength(); i++) {
			parseComplexType((Element) children.item(i), definition, namespace);
		}
		children = element.getElementsByTagNameNS(NS_EDM, "EntityContainer");
		for (int i = 0; i < children.getLength(); i++) {
			parseEntityContainer((Element) children.item(i), definition, namespace);
		}
	}
	
	private void parseEntityContainer(Element element, ODataDefinitionImpl definition, String namespace) {
		// an entity set is an actual set that provides access to a certain type
		// for example the type might be Person, the entity set might be People of type Person
		// we expose entitysets as functions
		NodeList children = element.getElementsByTagNameNS(NS_EDM, "EntitySet");
		for (int i = 0; i < children.getLength(); i++) {
			parseEntitySet(definition, (Element) children.item(i), namespace);
		}
	}
	

	private void parseEntitySet(ODataDefinitionImpl definition, Element childElement, String namespace) {
		String name = childElement.getAttribute("Name");
		boolean singleton = childElement.getLocalName().equals("Singleton");
		String typeName = childElement.getAttribute(singleton ? "Type" : "EntityType");
		Type type = getType(definition, typeName);
		
		// by default we can insert
//		boolean canInsert = !singleton && "true".equals(query(childElement, "edm:Annotation[@Term = 'Org.OData.Capabilities.V1.InsertRestrictions']/edm:Record/edm:PropertyValue[@Property='Insertable']/@Bool").asString("true"));
		boolean canInsert = !singleton && "true".equals(queryAsString(childElement, "edm:Annotation[@Term = 'Org.OData.Capabilities.V1.InsertRestrictions']/edm:Record/edm:PropertyValue[@Property='Insertable']/@Bool", "true"));
		boolean canSearch = !singleton && "true".equals(queryAsString(childElement, "edm:Annotation[@Term = 'Org.OData.Capabilities.V1.SearchRestrictions']/edm:Record/edm:PropertyValue[@Property='Searchable']/@Bool", "true"));
		boolean canDelete = !singleton && "true".equals(queryAsString(childElement, "edm:Annotation[@Term = 'Org.OData.Capabilities.V1.DeleteRestrictions']/edm:Record/edm:PropertyValue[@Property='Deletable']/@Bool", "true"));
		boolean canUpdate = true && "true".equals(queryAsString(childElement, "edm:Annotation[@Term = 'Org.OData.Capabilities.V1.UpdateRestrictions']/edm:Record/edm:PropertyValue[@Property='Updatable']/@Bool", "true"));
		// we presume you can always list/read though additional searching is not always guaranteed
		boolean listable = true;
		boolean gettable = listable;
		
		// if we can insert, we want to add a function for that
		// we make an extension of the original type
		// we will restrict where necessary and add nested items when relevant to perform batch inserts
		List<NavigationProperty> navigatableChildren = getNavigationPropertiesFor(definition, typeName);
		if (canInsert) {
			List<String> restrictedNames = new ArrayList<String>();
			Structure inputExtension = new Structure();
			inputExtension.setName(name + "Insert");
			inputExtension.setNamespace(namespace);
			inputExtension.setSuperType(type);
//			for (Element restricted : query(childElement, "edm:Annotation[@Term = 'Org.OData.Capabilities.V1.InsertRestrictions']/edm:Record/edm:PropertyValue[@Property='NonInsertableProperties']/edm:Collection/edm:PropertyPath").asElementList()) {
			restrictedNames.addAll(queryAsStringList(childElement, "edm:Annotation[@Term = 'Org.OData.Capabilities.V1.InsertRestrictions']/edm:Record/edm:PropertyValue[@Property='NonInsertableProperties']/edm:Collection/edm:PropertyPath"));
			// if we have restricted names within the fields, we have to restrict them from the parent type
			if (!restrictedNames.isEmpty()) {
				StringBuilder builder = new StringBuilder();
				for (String restrictedName : restrictedNames) {
					if (!builder.toString().isEmpty()) {
						builder.append(",");
					}
					builder.append(restrictedName);
				}
				inputExtension.setProperty(new ValueImpl<String>(RestrictProperty.getInstance(), builder.toString()));
			}
			// navigation properties are not present in the parent type so we don't need to restrict them, we just need to _not_ add them
//			for (Element restricted : query(childElement, "edm:Annotation[@Term = 'Org.OData.Capabilities.V1.InsertRestrictions']/edm:Record/edm:PropertyValue[@Property='NonInsertableNavigationProperties']/edm:Collection/edm:NavigationPropertyPath").asElementList()) {
			restrictedNames.addAll(queryAsStringList(childElement, "edm:Annotation[@Term = 'Org.OData.Capabilities.V1.InsertRestrictions']/edm:Record/edm:PropertyValue[@Property='NonInsertableNavigationProperties']/edm:Collection/edm:NavigationPropertyPath"));
			for (NavigationProperty navigatableChild : navigatableChildren) {
				// if we haven't restricted it, add it as an optional element (list?) to the insert
				if (!restrictedNames.contains(navigatableChild.getElement().getName())) {
					inputExtension.add(TypeBaseUtils.clone(navigatableChild.getElement(), inputExtension));
				}
			}
			Structure input = new Structure();
			input.setName("input");
			input.add(new ComplexElementImpl("create", inputExtension, input));
			Structure output = new Structure();
			output.setName("output");
			FunctionImpl insert = new FunctionImpl();
			insert.setContext(name);
			insert.setMethod("POST");
			insert.setInput(input);
			insert.setOutput(output);
			insert.setAction(true);
			insert.setName("create");
			definition.getFunctions().add(insert);
		}
		if (listable) {
			Structure input = new Structure();
			input.setName("input");
			if (canSearch) {
				input.add(new SimpleElementImpl<String>("query", SimpleTypeWrapperFactory.getInstance().getWrapper().wrap(String.class), input, new ValueImpl<Integer>(MinOccursProperty.getInstance(), 0)));
			}
			Structure output = new Structure();
			output.setName("output");
			output.add(new ComplexElementImpl("results", (ComplexType) type, output,
				new ValueImpl<Integer>(MinOccursProperty.getInstance(), 0),
				new ValueImpl<Integer>(MaxOccursProperty.getInstance(), 0),
				new ValueImpl<String>(AliasProperty.getInstance(), "value")));
			FunctionImpl list = new FunctionImpl();
			list.setContext(name);
			list.setInput(input);
			list.setOutput(output);
			list.setMethod("GET");
			list.setName("list");
			definition.getFunctions().add(list);
		}
		if (gettable) {
			Structure input = new Structure();
			input.setName("input");
			boolean foundPrimary = false;
			for (be.nabu.libs.types.api.Element<?> child : TypeUtils.getAllChildren(((ComplexType) type))) {
				Boolean primaryKey = ValueUtils.getValue(PrimaryKeyProperty.getInstance(), child.getProperties());
				if (primaryKey != null && primaryKey) {
					be.nabu.libs.types.api.Element<?> cloned = TypeBaseUtils.clone(child, input);
					// we want to standardize the name
					// standardizing the name makes it easier to predict the input but less obvious for users which field the actual key is
//					cloned.setProperty(new ValueImpl<String>(NameProperty.getInstance(), "id"));
					// in such a case it is mandatory!
					cloned.setProperty(new ValueImpl<Integer>(MinOccursProperty.getInstance(), 1));
					input.add(cloned);
					foundPrimary = true;
				}
			}
			if (foundPrimary) {
				Structure output = new Structure();
				output.setName("output");
				output.add(new ComplexElementImpl("result", (ComplexType) type, output,
					new ValueImpl<Integer>(MinOccursProperty.getInstance(), 0)));
				FunctionImpl get = new FunctionImpl();
				get.setContext(name);
				get.setInput(input);
				get.setOutput(output);
				get.setMethod("GET");
				get.setName("get");
				definition.getFunctions().add(get);
			}
		}
		if (canDelete) {
			Structure input = new Structure();
			input.setName("input");
			boolean foundPrimary = false;
			for (be.nabu.libs.types.api.Element<?> child : TypeUtils.getAllChildren(((ComplexType) type))) {
				Boolean primaryKey = ValueUtils.getValue(PrimaryKeyProperty.getInstance(), child.getProperties());
				if (primaryKey != null && primaryKey) {
					be.nabu.libs.types.api.Element<?> cloned = TypeBaseUtils.clone(child, input);
					// we want to standardize the name
//					cloned.setProperty(new ValueImpl<String>(NameProperty.getInstance(), "id"));
					// in such a case it is mandatory!
					cloned.setProperty(new ValueImpl<Integer>(MinOccursProperty.getInstance(), 1));
					input.add(cloned);
					foundPrimary = true;
				}
			}
			if (foundPrimary) {
				Structure output = new Structure();
				output.setName("output");
				FunctionImpl delete = new FunctionImpl();
				delete.setContext(name);
				delete.setInput(input);
				delete.setOutput(output);
				delete.setMethod("DELETE");
				delete.setName("delete");
				definition.getFunctions().add(delete);
			}
		}
		// when we say updates, we mean puts where the entire object is updated rather than just part of it
		if (canUpdate) {
			List<String> restrictedNames = new ArrayList<String>();
			Structure inputExtension = new Structure();
			inputExtension.setName(name + "Update");
			inputExtension.setNamespace(namespace);
			inputExtension.setSuperType(type);
//			for (Element restricted : query(childElement, "edm:Annotation[@Term = 'Org.OData.Capabilities.V1.InsertRestrictions']/edm:Record/edm:PropertyValue[@Property='NonInsertableProperties']/edm:Collection/edm:PropertyPath").asElementList()) {
			restrictedNames.addAll(queryAsStringList(childElement, "edm:Annotation[@Term = 'Org.OData.Capabilities.V1.UpdateRestrictions']/edm:Record/edm:PropertyValue[@Property='NonUpdatableProperties']/edm:Collection/edm:PropertyPath"));
			// if we have restricted names within the fields, we have to restrict them from the parent type
			if (!restrictedNames.isEmpty()) {
				StringBuilder builder = new StringBuilder();
				for (String restrictedName : restrictedNames) {
					if (!builder.toString().isEmpty()) {
						builder.append(",");
					}
					builder.append(restrictedName);
				}
				inputExtension.setProperty(new ValueImpl<String>(RestrictProperty.getInstance(), builder.toString()));
			}
			Structure input = new Structure();
			input.setName("input");
			boolean foundPrimary = false;
			for (be.nabu.libs.types.api.Element<?> child : TypeUtils.getAllChildren(((ComplexType) type))) {
				Boolean primaryKey = ValueUtils.getValue(PrimaryKeyProperty.getInstance(), child.getProperties());
				if (primaryKey != null && primaryKey) {
					be.nabu.libs.types.api.Element<?> cloned = TypeBaseUtils.clone(child, input);
					// we want to standardize the name
//					cloned.setProperty(new ValueImpl<String>(NameProperty.getInstance(), "id"));
					// in such a case it is mandatory!
					cloned.setProperty(new ValueImpl<Integer>(MinOccursProperty.getInstance(), 1));
					input.add(cloned);
					foundPrimary = true;
				}
			}
			if (foundPrimary) {
				input.add(new ComplexElementImpl("update", inputExtension, input));
				Structure output = new Structure();
				output.setName("input");
				FunctionImpl update = new FunctionImpl();
				update.setContext(name);
				update.setContext(name);
				update.setMethod("PUT");
				update.setInput(input);
				update.setOutput(output);
				update.setAction(true);
				update.setName("update");
				definition.getFunctions().add(update);
			}
		}
	}
	
	private List<NavigationProperty> getNavigationPropertiesFor(ODataDefinition definition, String qualifiedName) {
		List<NavigationProperty> navigatableChildren = new ArrayList<NavigationProperty>();
		for (NavigationProperty property : definition.getNavigationProperties()) {
			if (property.getQualifiedName().equals(qualifiedName)) {
				navigatableChildren.add(property);
			}
		}
		return navigatableChildren;
	}
	
	private boolean optimizedQuerying = true;
	
	private List<Element> queryAsElementList(Element element, String query) {
		if (optimizedQuerying) {
			Object queryContent = queryContent(element, query);
			List<Element> result = new ArrayList<Element>();
			if (queryContent == null) {
				return result;
			}
			if (!(queryContent instanceof List)) {
				queryContent = Arrays.asList(queryContent);
			}
			for (Object single : (List<?>) queryContent) {
				if (single == null) {
					continue;
				}
				result.add(((XMLContent) single).getElement());
			}
			return result;
		}
		else {
			return query(element, query).asElementList();
		}
	}
	
	private List<String> queryAsStringList(Element element, String query) {
		List<String> result = new ArrayList<String>();
		if (optimizedQuerying) {
			Object queryContent = queryContent(element, query);
			if (queryContent == null) {
				return result;
			}
			if (!(queryContent instanceof List)) {
				queryContent = Arrays.asList(queryContent);
			}
			for (Object single : (List<?>) queryContent) {
				if (single == null) {
					continue;
				}
				result.add((String) single);
			}
		}
		else {
			List<Element> asElementList = query(element, query).asElementList();
			for (Element single : asElementList) {
				result.add(single.getTextContent().trim());
			}
		}
		return result;
	}
	private String queryAsString(Element element, String query, String defaultValue) {
		if (optimizedQuerying) {
			Object result = queryContent(element, query);
			if (result instanceof List) {
				result = ((List<?>) result).size() > 0 ? ((List<?>) result).get(0) : null;
			}
			if (result == null) {
				result = defaultValue;
			}
			return (String) result;
		}
		else {
			return query(element, query).asString(defaultValue);
		}
	}

	private Object queryContent(Element element, String query) {
		// we don't care about namespaces
		query = query
			.replace("edm:", "")
			.replace("edmx:", "");
		try {
			if (pathAnalyzer == null) {
				pathAnalyzer = new PathAnalyzer<Object>(new PlainOperationProvider());
			}
			Operation<Object> analyze = pathAnalyzer.analyze(QueryParser.getInstance().parse(query));
			return analyze.evaluate(new XMLContent(element));
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
	
	private XPath query(Node node, String query) {
		Map<String, String> prefixes = new HashMap<String, String>();
		prefixes.put("edm", NS_EDM);
		prefixes.put("edmx", NS_EDMX);
		BaseNamespaceResolver resolver = new BaseNamespaceResolver();
		resolver.setPrefixes(prefixes);
		resolver.setScanRecursively(true);
		return new XPath(query).setNamespaceContext(resolver).query(node);
	}
	
	private void parseEnumType(Element element, ODataDefinitionImpl definition, String namespace) {
		// we currently parse this as a simple type string with an enumeration on it
		String name = element.getAttribute("Name");
		EnumType enumType = new EnumType(namespace, name);
		List<String> values = new ArrayList<String>();
		NodeList children = element.getElementsByTagNameNS(NS_EDM, "Member");
		for (int i = 0; i < children.getLength(); i++) {
			values.add(((Element) children.item(i)).getAttribute("Name"));
		}
		logger.debug("Parsing enum " + name + ": "+ values);
		enumType.setProperty(new ValueImpl<List<String>>(new EnumerationProperty<String>(), values));
		((TypeRegistryImpl) definition.getRegistry()).register(enumType);
	}
	
	// we do an initial run over the complex types so we have _a_ functional reference of all types before we start parsing
	// the problem is as ever: the order of the complex types is not guaranteed, if the first type reference the second type it is syntactically correct but sequentially unknown at that point
	private void preparseComplexType(Element element, ODataDefinitionImpl definition, String namespace) {
		String name = element.getAttribute("Name");
		logger.debug("Preparsing complex type " + name);
		DefinedStructure structure = new DefinedStructure();
		structure.setName(name);
		structure.setNamespace(namespace);
		
		((TypeRegistryImpl) definition.getRegistry()).register(structure);

		String id = baseId == null ? "" : baseId + ".";
		// we parse ComplexType with the same code as entity type but complextype have no persistence, they are helper definitions
		// if we are an entity type, we add a collection name as a marker of persistence
		if (element.getLocalName().equals("EntityType")) {
			structure.setProperty(new ValueImpl<String>(CollectionNameProperty.getInstance(), name));
			id += "entities.";
		}
		else {
			id += "types.";
		}
		structure.setId(id + name);
	}
	
	private void parseComplexType(Element element, ODataDefinitionImpl definition, String namespace) {
		String name = element.getAttribute("Name");
		logger.debug("Parsing complex type " + name);
		Structure structure = (Structure) definition.getRegistry().getComplexType(namespace, name);
		if (element.hasAttribute("BaseType")) {
			structure.setSuperType(getType(definition, element.getAttribute("BaseType")));
		}
		NodeList children = element.getElementsByTagNameNS(NS_EDM, "Property");
		for (int i = 0; i < children.getLength(); i++) {
			structure.add(buildElement(definition, structure, (Element) children.item(i)));
		}
		children = element.getElementsByTagNameNS(NS_EDM, "NavigationProperty");
		for (int i = 0; i < children.getLength(); i++) {
			NavigationPropertyImpl navigation = new NavigationPropertyImpl();
			navigation.setElement(buildElement(definition, structure, (Element) children.item(i)));
			navigation.setQualifiedName(namespace + "." + name);
			definition.getNavigationProperties().add(navigation);
		}
//		List<Element> keyList = query(element, "edm:Key/edm:PropertyRef").asElementList();
		List<String> keyList = queryAsStringList(element, "edm:Key/edm:PropertyRef/@Name");
		// if we have exactly one key, mark it as primary
		if (keyList.size() == 1) {
			String primaryKeyName = keyList.get(0);
			if (primaryKeyName != null) {
				be.nabu.libs.types.api.Element<?> primaryKeyElement = structure.get(primaryKeyName);
				if (primaryKeyElement != null) {
					primaryKeyElement.setProperty(new ValueImpl<Boolean>(PrimaryKeyProperty.getInstance(), true));
				}
			}
		}
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	private be.nabu.libs.types.api.Element<?> buildElement(ODataDefinitionImpl definition, Structure structure, Element child) {
		String childName = child.getAttribute("Name");
		String childType = child.getAttribute("Type");
		List<Value<?>> values = new ArrayList<Value<?>>();
		if (childType.startsWith("Collection(")) {
			values.add(new ValueImpl<Integer>(MaxOccursProperty.getInstance(), 0));
			// skip the closing ) as well
			childType = childType.substring("Collection(".length(), childType.length() - 1);
		}
		logger.debug("\tParsing child " + childName + " of type " + childType);
		Type type = getType(definition, childType);
		// we assume default nillable? not sure...
		boolean nillable = !child.hasAttribute("Nullable") || "true".equals(child.getAttribute("Nullable"));
		values.add(new ValueImpl<Boolean>(NillableProperty.getInstance(), nillable));
		values.add(new ValueImpl<Integer>(MinOccursProperty.getInstance(), nillable ? 0 : 1 ));
		if (type instanceof ComplexType) {
			return new ComplexElementImpl(childName, (ComplexType) type, structure, values.toArray(new Value[0]));
		}
		else {
			return new SimpleElementImpl(childName, (SimpleType<?>) type, structure, values.toArray(new Value[0]));
		}
	}
	
	private Type getType(ODataDefinition definition, String name) {
		// A point representing a geographic location on the globe. For request and response bodies the representation of values of this type follows the GeoJSON "Point" type format.
		// For URLs OData uses a literal form based on the WKT standard. A point literal is constructed as geography'POINT(lon lat)'.
		if (name.equals("Edm.GeographyPoint")) {
			return BeanResolver.getInstance().resolve(GeographyPoint.class);
		}
		else if (name.startsWith("Edm.")) {
			Class<?> wrapper = null;
			if ("Edm.String".equals(name)) {
				wrapper = String.class;
			}
			else if ("Edm.Binary".equals(name)) {
				wrapper = byte[].class;
			}
			else if ("Edm.Boolean".equals(name)) {
				wrapper = Boolean.class;
			}
			// it is unsigned in OData, so we actually put it in a short
			else if ("Edm.Byte".equals(name)) {
				wrapper = Short.class;
			}
			// signed byte
			else if ("Edm.SByte".equals(name)) {
				wrapper = Byte.class;
			}
			else if ("Edm.DateTime".equals(name)) {
				wrapper = Date.class;
			}
			else if ("Edm.Date".equals(name)) {
				wrapper = Date.class;
			}
			else if ("Edm.Time".equals(name)) {
				wrapper = Date.class;
			}
			// Contains a date and time as an offset in minutes from GMT.
			else if ("Edm.DateTimeOffset".equals(name)) {
				wrapper = Integer.class;
			}
			// Numeric values with fixed precision and scale
			// we might want to change this to bigdecimal in the future...
			else if ("Edm.Decimal".equals(name)) {
				wrapper = Double.class;
			}
			// A floating point number with 15 digits precision
			else if ("Edm.Double".equals(name)) {
				wrapper = Double.class;
			}
			else if ("Edm.Float".equals(name)) {
				wrapper = Float.class;
			}
			// 	A floating point number with 7 digits precision
			// not sure why this exists...
			else if ("Edm.Single".equals(name)) {
				wrapper = Float.class;
			}
			else if ("Edm.Guid".equals(name)) {
				wrapper = UUID.class;
			}
			else if ("Edm.Int16".equals(name)) {
				wrapper = Short.class;
			}
			else if ("Edm.Int32".equals(name)) {
				wrapper = Integer.class;
			}
			else if ("Edm.Int64".equals(name)) {
				wrapper = Long.class;
			}
			else if ("Edm.Duration".equals(name)) {
				wrapper = Duration.class;
			}
			else {
				throw new IllegalArgumentException("Unknown Edm type: " + name);
			}
			return SimpleTypeWrapperFactory.getInstance().getWrapper().wrap(wrapper);
		}
		else {
			if (name.trim().isEmpty()) {
				throw new IllegalArgumentException("An empty type was requested");
			}
			// it is generally a full name with namespace and name together
			int index = name.lastIndexOf('.');
			if (index < 0) {
				throw new IllegalArgumentException("The requested type is not fully qualified: " + name);
			}
			String typeNamespace = name.substring(0, index);
			if (aliases.containsKey(typeNamespace)) {
				typeNamespace = aliases.get(typeNamespace);
			}
			String typeName = name.substring(index + 1);
			ComplexType complexType = definition.getRegistry().getComplexType(typeNamespace, typeName);
			if (complexType != null) {
				return complexType;
			}
			SimpleType<?> simpleType = definition.getRegistry().getSimpleType(typeNamespace, typeName);
			if (simpleType != null) {
				return simpleType;
			}
			throw new IllegalArgumentException("Unknown type: " + name);
		}
	}

	public String getBaseId() {
		return baseId;
	}

	public void setBaseId(String baseId) {
		this.baseId = baseId;
	}
}
