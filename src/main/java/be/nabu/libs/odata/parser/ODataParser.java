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
import be.nabu.libs.types.api.DefinedType;
import be.nabu.libs.types.api.ModifiableElement;
import be.nabu.libs.types.api.ModifiableType;
import be.nabu.libs.types.api.SimpleType;
import be.nabu.libs.types.api.Type;
import be.nabu.libs.types.base.ComplexElementImpl;
import be.nabu.libs.types.base.Duration;
import be.nabu.libs.types.base.SimpleElementImpl;
import be.nabu.libs.types.base.TypeBaseUtils;
import be.nabu.libs.types.base.UUIDFormat;
import be.nabu.libs.types.base.ValueImpl;
import be.nabu.libs.types.java.BeanResolver;
import be.nabu.libs.types.properties.AliasProperty;
import be.nabu.libs.types.properties.CollectionNameProperty;
import be.nabu.libs.types.properties.DuplicateProperty;
import be.nabu.libs.types.properties.EnumerationProperty;
import be.nabu.libs.types.properties.ForeignKeyProperty;
import be.nabu.libs.types.properties.ForeignNameProperty;
import be.nabu.libs.types.properties.MaxOccursProperty;
import be.nabu.libs.types.properties.MinOccursProperty;
import be.nabu.libs.types.properties.NameProperty;
import be.nabu.libs.types.properties.NillableProperty;
import be.nabu.libs.types.properties.PrimaryKeyProperty;
import be.nabu.libs.types.properties.RestrictProperty;
import be.nabu.libs.types.properties.UUIDFormatProperty;
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
 * 
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
	
	private List<ODataExpansion> expansions;
	
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
		// allow for out-of-order things to reference each other
		for (int i = 0; i < children.getLength(); i++) {
			preparseSchema((Element) children.item(i), definition);
		}
		for (int i = 0; i < children.getLength(); i++) {
			parseSchema((Element) children.item(i), definition);
		}
	}
	
	private void preparseSchema(Element element, ODataDefinitionImpl definition) {
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
	}
	
	private void parseSchema(Element element, ODataDefinitionImpl definition) {
		String namespace = element.getAttribute("Namespace");
		String alias = element.getAttribute("Alias");
		if (alias != null && !alias.trim().isEmpty()) {
			aliases.put(alias, namespace);
		}
		// things that need to run after the parsing is done
		List<Runnable> runnables = new ArrayList<Runnable>();
		// actual parsing
		NodeList children = element.getElementsByTagNameNS(NS_EDM, "ComplexType");
		for (int i = 0; i < children.getLength(); i++) {
			runnables.addAll(parseComplexType((Element) children.item(i), definition, namespace));
		}
		children = element.getElementsByTagNameNS(NS_EDM, "EntityType");
		for (int i = 0; i < children.getLength(); i++) {
			runnables.addAll(parseComplexType((Element) children.item(i), definition, namespace));
		}
		children = element.getElementsByTagNameNS(NS_EDM, "EntityContainer");
		for (int i = 0; i < children.getLength(); i++) {
			parseEntityContainer((Element) children.item(i), definition, namespace);
		}
		for (Runnable runnable : runnables) {
			runnable.run();
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
		
		// update the collection name!
		((ModifiableType) type).setProperty(new ValueImpl<String>(CollectionNameProperty.getInstance(), name));
		
		// by default we can insert
//		boolean canInsert = !singleton && "true".equals(query(childElement, "edm:Annotation[@Term = 'Org.OData.Capabilities.V1.InsertRestrictions']/edm:Record/edm:PropertyValue[@Property='Insertable']/@Bool").asString("true"));
		boolean canInsert = !singleton && "true".equals(queryAsString(childElement, "edm:Annotation[@Term = 'Org.OData.Capabilities.V1.InsertRestrictions']/edm:Record/edm:PropertyValue[@Property='Insertable']/@Bool", "true"));
		// searching is basically a "q" parameter, it searches object wide and uses a contains
		// not supported everywhere
		boolean canSearch = !singleton && "true".equals(queryAsString(childElement, "edm:Annotation[@Term = 'Org.OData.Capabilities.V1.SearchRestrictions']/edm:Record/edm:PropertyValue[@Property='Searchable']/@Bool", "true"));
		// filter is supported more frequently, does not allow a contains and allows targeting specific fields
		boolean canFilter = !singleton && "true".equals(queryAsString(childElement, "edm:Annotation[@Term = 'Org.OData.Capabilities.V1.SelectSupport']/edm:Record/edm:PropertyValue[@Property='Filterable']/@Bool", "true"));
		// if either one is set to false, we can't do it
		canSearch &= "true".equals(queryAsString(childElement, "edm:Annotation[@Term = 'Org.OData.Capabilities.V1.SelectSupport']/edm:Record/edm:PropertyValue[@Property='Searchable']/@Bool", "true"));
		boolean canDelete = !singleton && "true".equals(queryAsString(childElement, "edm:Annotation[@Term = 'Org.OData.Capabilities.V1.DeleteRestrictions']/edm:Record/edm:PropertyValue[@Property='Deletable']/@Bool", "true"));
		boolean canUpdate = true && "true".equals(queryAsString(childElement, "edm:Annotation[@Term = 'Org.OData.Capabilities.V1.UpdateRestrictions']/edm:Record/edm:PropertyValue[@Property='Updatable']/@Bool", "true"));
		// we presume you can always list/read though additional searching is not always guaranteed
		boolean listable = true;
		boolean gettable = listable;
		
		boolean canSkip = !singleton && "true".equals(queryAsString(childElement, "edm:Annotation[@Term = 'Org.OData.Capabilities.V1.SkipSupported']/@Bool", "true"));
		canSkip &= "true".equals(queryAsString(childElement, "edm:Annotation[@Term = 'Org.OData.Capabilities.V1.SelectSupport']/edm:Record/edm:PropertyValue[@Property='SkipSupported']/@Bool", "true"));
		boolean canTop = !singleton && "true".equals(queryAsString(childElement, "edm:Annotation[@Term = 'Org.OData.Capabilities.V1.TopSupported']/@Bool", "true"));
		canTop &= "true".equals(queryAsString(childElement, "edm:Annotation[@Term = 'Org.OData.Capabilities.V1.SelectSupport']/edm:Record/edm:PropertyValue[@Property='TopSupported']/@Bool", "true"));
		boolean canCount = !singleton && "true".equals(queryAsString(childElement, "edm:Annotation[@Term = 'Org.OData.Capabilities.V1.CountRestrictions']/edm:Record/edm:PropertyValue[@Property='Countable']/@Bool", "true"));
		canCount &= "true".equals(queryAsString(childElement, "edm:Annotation[@Term = 'Org.OData.Capabilities.V1.SelectSupport']/edm:Record/edm:PropertyValue[@Property='CountSupported']/@Bool", "true"));
		
		boolean canOrder = !singleton && "true".equals(queryAsString(childElement, "edm:Annotation[@Term = 'Org.OData.Capabilities.V1.SelectSupport']/edm:Record/edm:PropertyValue[@Property='Sortable']/@Bool", "true"));
		
		// if we can insert, we want to add a function for that
		// we make an extension of the original type
		// we will restrict where necessary and add nested items when relevant to perform batch inserts
		List<NavigationProperty> navigatableChildren = getNavigationPropertiesFor(definition, typeName);
		if (canInsert) {
			List<String> restrictedNames = new ArrayList<String>();
			DefinedStructure inputExtension = new DefinedStructure();
			inputExtension.setName(name + "Insert");
			inputExtension.setNamespace(namespace);
			inputExtension.setSuperType(type);
			inputExtension.setId(((DefinedType) type).getId() + "Insert");
			((TypeRegistryImpl) definition.getRegistry()).register(inputExtension);
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
				String collectionName = queryAsString(childElement, "edm:NavigationPropertyBinding[@Path='" + navigatableChild.getElement().getName() + "']/@Target", null);
				// if we haven't restricted it, add it as an optional element (list?) to the insert
				if (!restrictedNames.contains(navigatableChild.getElement().getName())) {
					be.nabu.libs.types.api.Element<?> clone = TypeBaseUtils.clone(navigatableChild.getElement(), inputExtension);
					clone.setProperty(new ValueImpl<String>(CollectionNameProperty.getInstance(), collectionName));
					inputExtension.add(clone);
					SimpleElementImpl<String> bindingElement = new SimpleElementImpl<String>(navigatableChild.getElement().getName() + "@odata.bind", SimpleTypeWrapperFactory.getInstance().getWrapper().wrap(String.class), inputExtension, new ValueImpl<Integer>(MinOccursProperty.getInstance(), 0));
					// if it's a list, we also want a binding list
					Integer maxOccurs = ValueUtils.getValue(MaxOccursProperty.getInstance(), clone.getProperties());
					if (maxOccurs != null) {
						bindingElement.setProperty(new ValueImpl<Integer>(MaxOccursProperty.getInstance(), maxOccurs));
					}
					inputExtension.add(bindingElement);
				}
			}
			Structure input = new Structure();
			input.setName("input");
			input.add(new ComplexElementImpl("create", inputExtension, input));
			Structure output = new Structure();
			output.setName("output");
			
			be.nabu.libs.types.api.Element<?> primaryKey = null;
			for (be.nabu.libs.types.api.Element<?> child : TypeUtils.getAllChildren((ComplexType) type)) {
				Boolean value = ValueUtils.getValue(PrimaryKeyProperty.getInstance(), child.getProperties());
				if (value != null && value) {
					primaryKey = child;
				}
			}
			if (primaryKey != null) {
				be.nabu.libs.types.api.Element<?> cloned = TypeBaseUtils.clone(primaryKey, output);
				output.add(cloned);
			}
			
			FunctionImpl insert = new FunctionImpl();
			insert.setContext(name);
			insert.setMethod("POST");
			insert.setInput(input);
			insert.setOutput(output);
			insert.setAction(true);
			insert.setName("create");
			definition.getFunctions().add(insert);
		}
		
		// let's always build the select extension, you will likely always need it and we want to plug it in into both the list and get
		DefinedStructure selectExtension = new DefinedStructure();
		selectExtension.setName(name + "Select");
		selectExtension.setNamespace(namespace);
		selectExtension.setSuperType(type);
		// we need to inherit at the very least the collection name
		String originalCollectionName = ValueUtils.getValue(CollectionNameProperty.getInstance(), type.getProperties());
		if (originalCollectionName != null) {
			selectExtension.setProperty(new ValueImpl<String>(CollectionNameProperty.getInstance(), originalCollectionName));
		}
		
		selectExtension.setId(((DefinedType) type).getId() + "Select");
		((TypeRegistryImpl) definition.getRegistry()).register(selectExtension);
		String structureId = ((DefinedType) type).getId();
		List<String> expansions = new ArrayList<String>();
		if (this.expansions != null) {
			for (ODataExpansion expansion : this.expansions) {
				if (structureId.equals(expansion.getEntity()) && expansion.getExpansions() != null) {
					expansions.addAll(expansion.getExpansions());
				}
			}
		}
		if (!expansions.isEmpty()) {
			StringBuilder duplicate = new StringBuilder();
			for (NavigationProperty navigatableChild : navigatableChildren) {
				if (expansions.indexOf(navigatableChild.getElement().getName()) >= 0) {
					be.nabu.libs.types.api.Element<?> cloned = TypeBaseUtils.clone(navigatableChild.getElement(), selectExtension);
					selectExtension.add(cloned);
					if (!duplicate.toString().isEmpty()) {
						duplicate.append(",");
					}
					duplicate.append(navigatableChild.getElement().getName());
				}
			}
			if (!duplicate.toString().isEmpty()) {
				selectExtension.setProperty(new ValueImpl<String>(DuplicateProperty.getInstance(), duplicate.toString()));
			}
		}
		
		if (listable) {
			Structure input = new Structure();
			input.setName("input");
			if (canSearch) {
				input.add(new SimpleElementImpl<String>("search", SimpleTypeWrapperFactory.getInstance().getWrapper().wrap(String.class), input, new ValueImpl<Integer>(MinOccursProperty.getInstance(), 0)));
			}
			if (canFilter) {
				input.add(new SimpleElementImpl<String>("filter", SimpleTypeWrapperFactory.getInstance().getWrapper().wrap(String.class), input, new ValueImpl<Integer>(MinOccursProperty.getInstance(), 0)));
			}
			if (canOrder) {
				input.add(new SimpleElementImpl<String>("orderBy", SimpleTypeWrapperFactory.getInstance().getWrapper().wrap(String.class), input, new ValueImpl<Integer>(MinOccursProperty.getInstance(), 0), new ValueImpl<Integer>(MaxOccursProperty.getInstance(), 0)));
			}
			if (canTop) {
				input.add(new SimpleElementImpl<Integer>("limit", SimpleTypeWrapperFactory.getInstance().getWrapper().wrap(Integer.class), input, new ValueImpl<Integer>(MinOccursProperty.getInstance(), 0)));
			}
			if (canSkip) {
				input.add(new SimpleElementImpl<Long>("offset", SimpleTypeWrapperFactory.getInstance().getWrapper().wrap(Long.class), input, new ValueImpl<Integer>(MinOccursProperty.getInstance(), 0)));
			}
			if (canCount) {
				input.add(new SimpleElementImpl<Boolean>("totalCount", SimpleTypeWrapperFactory.getInstance().getWrapper().wrap(Boolean.class), input, new ValueImpl<Integer>(MinOccursProperty.getInstance(), 0)));
			}
			Structure output = new Structure();
			output.setName("output");
			
			output.add(new ComplexElementImpl("results", selectExtension, output,
				new ValueImpl<Integer>(MinOccursProperty.getInstance(), 0),
				new ValueImpl<Integer>(MaxOccursProperty.getInstance(), 0),
				new ValueImpl<String>(AliasProperty.getInstance(), "value")));
		
			if (canCount) {
				output.add(new SimpleElementImpl<Long>("count", SimpleTypeWrapperFactory.getInstance().getWrapper().wrap(Long.class), input, 
					new ValueImpl<Integer>(MinOccursProperty.getInstance(), 0),
					new ValueImpl<String>(AliasProperty.getInstance(), "@odata.count")
				));
			}
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
//				output.add(new ComplexElementImpl("result", (ComplexType) type, output,
//					new ValueImpl<Integer>(MinOccursProperty.getInstance(), 0)));
				output.add(new ComplexElementImpl("result", selectExtension, output,
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
			DefinedStructure inputExtension = new DefinedStructure();
			inputExtension.setName(name + "Update");
			inputExtension.setNamespace(namespace);
			inputExtension.setSuperType(type);
			inputExtension.setId(((DefinedType) type).getId() + "Update");
			((TypeRegistryImpl) definition.getRegistry()).register(inputExtension);
//			for (Element restricted : query(childElement, "edm:Annotation[@Term = 'Org.OData.Capabilities.V1.InsertRestrictions']/edm:Record/edm:PropertyValue[@Property='NonInsertableProperties']/edm:Collection/edm:PropertyPath").asElementList()) {
			restrictedNames.addAll(queryAsStringList(childElement, "edm:Annotation[@Term = 'Org.OData.Capabilities.V1.UpdateRestrictions']/edm:Record/edm:PropertyValue[@Property='NonUpdatableProperties']/edm:Collection/edm:PropertyPath"));
			
			// we want to restrict all fields that have a foreign key, they can not be updated as is but need special handling
//			for (be.nabu.libs.types.api.Element<?> child : TypeUtils.getAllChildren((ComplexType) type)) {
//				String foreignKey = ValueUtils.getValue(ForeignKeyProperty.getInstance(), child.getProperties());
//				if (foreignKey != null) {
//					restrictedNames.add(child.getName());
//				}
//				be.nabu.libs.types.api.Element<?> cloned = TypeBaseUtils.clone(child, inputExtension);
//				cloned.setProperty(new ValueImpl<String>(AliasProperty.getInstance(), ""));
//				inputExtension.add(cloned);
//			}
			
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
			
			for (NavigationProperty navigatableChild : navigatableChildren) {
				String collectionName = queryAsString(childElement, "edm:NavigationPropertyBinding[@Path='" + navigatableChild.getElement().getName() + "']/@Target", null);
				// if we haven't restricted it, add it as an optional element (list?) to the insert
				if (!restrictedNames.contains(navigatableChild.getElement().getName())) {
					be.nabu.libs.types.api.Element<?> clone = TypeBaseUtils.clone(navigatableChild.getElement(), inputExtension);
					// we want to set the collection name to the correct one for this entity set OR to null at which point the collection name on the type itself should be used
					clone.setProperty(new ValueImpl<String>(CollectionNameProperty.getInstance(), collectionName));
					inputExtension.add(clone);
					SimpleElementImpl<String> bindingElement = new SimpleElementImpl<String>(navigatableChild.getElement().getName() + "@odata.bind", SimpleTypeWrapperFactory.getInstance().getWrapper().wrap(String.class), inputExtension, new ValueImpl<Integer>(MinOccursProperty.getInstance(), 0));
					// if it's a list, we also want a binding list
					Integer maxOccurs = ValueUtils.getValue(MaxOccursProperty.getInstance(), clone.getProperties());
					if (maxOccurs != null) {
						bindingElement.setProperty(new ValueImpl<Integer>(MaxOccursProperty.getInstance(), maxOccurs));
					}
					inputExtension.add(bindingElement);
				}
			}
			
			Structure input = new Structure();
			input.setName("input");
			
			// for the update, adding the primary key to the actual function input is often unnecessary duplication because it will also be in the body
			// however, in the future we might allow for patch updates which are not guaranteed to have the primary key in the body
			// additionally, the existence of the primary key in the function input is taken as an indicator that the eventual call needs the primary key in the target path
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
				// a patch with the full document is the same as a PUT
				// according to the specifications a PATCH _should_ be supported, a PUT _may_ be supported
				update.setMethod("PATCH");
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
	
	private List<Runnable> parseComplexType(Element element, ODataDefinitionImpl definition, String namespace) {
		List<Runnable> runnables = new ArrayList<Runnable>();
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
			NodeList constraints = ((Element) children.item(i)).getElementsByTagNameNS(NS_EDM, "ReferentialConstraint");
			// for updating, we can't actually push the foreign keys themselves, we need to wrap it
			// for example for a particular field called _transactioncurrencyid_value, we have a referenced navigation property, updating it we need to generate this:
			// "transactioncurrencyid@odata.bind": "/transactioncurrencies(7e88f4dc-7131-ed11-9db1-000d3a2afebd)",
			// however, references to another type can exist of multiple keys at which point we need something like "/transactioncurrencies(id=...,other=...)"
			// note also that foreign keys might not be strings, but to push this we always need to set a string
			Structure updateStructure = new Structure();
			// we want the name of this element
			updateStructure.setName(navigation.getElement().getName());
			boolean referenced = false;
			for (int j = 0; j < constraints.getLength(); j++) {
				// the name of the property in _this_ type
				String propertyName = ((Element) constraints.item(j)).getAttribute("Property");
				// the name of the property in the referenced type
				String referencedPropertyName = ((Element) constraints.item(j)).getAttribute("ReferencedProperty");
				// the referenced type is not guaranteed to be loaded at this point!
				// we wanted to take the actual types to get the actual data types, but we don't "really" need it
				// we can just set it as string, it is currently just a very nasty marker structure which is not actually used as an instance, but we just use the metadata
				// this should be refactored at some point
//				be.nabu.libs.types.api.Element<?> targetType = ((ComplexType) navigation.getElement().getType()).get(referencedPropertyName);
//				be.nabu.libs.types.api.Element<?> cloned = TypeBaseUtils.clone(targetType, updateStructure);
				be.nabu.libs.types.api.Element<?> cloned = new SimpleElementImpl<String>(referencedPropertyName, SimpleTypeWrapperFactory.getInstance().getWrapper().wrap(String.class), updateStructure);
				// we set the original property as the alias so we know what to look for
				cloned.setProperty(new ValueImpl<String>(AliasProperty.getInstance(), propertyName));
				updateStructure.add(cloned);
				navigation.setUpdateType(updateStructure);
				
				// we also update the affected element to have a foreign key
				be.nabu.libs.types.api.Element<?> affectedChildElement = structure.get(propertyName);
				// we set a foreign key on this
				// we need to update these differently if we actually update them
				if (affectedChildElement != null) {
					affectedChildElement.setProperty(new ValueImpl<String>(ForeignKeyProperty.getInstance(), ((DefinedType) navigation.getElement().getType()).getId() + ":" + referencedPropertyName));
					affectedChildElement.setProperty(new ValueImpl<String>(ForeignNameProperty.getInstance(), navigation.getElement().getName()));
					// this is only relevant for the updates, but when parsing both the normal name and the alias should be possible to use
//					affectedChildElement.setProperty(new ValueImpl<String>(AliasProperty.getInstance(), navigation.getElement().getName() + "@odata.bind"));
					referenced = true;
				}
			}
			// if we don't have a field INSIDE the structure itself that can be used for binding, we add one
			if (!referenced) {
				// we can only do this asynchronously after everything is parsed because we need to inspect the target structures
				// they may come after us in parsing
				Runnable linker = new Runnable() {
					public void run() {
						be.nabu.libs.types.api.Element<?> primaryKey = null;
						// find the primary key in the target type
						for (be.nabu.libs.types.api.Element<?> targetChild : TypeUtils.getAllChildren((ComplexType) navigation.getElement().getType())) {
							Boolean isPrimaryKey = ValueUtils.getValue(PrimaryKeyProperty.getInstance(), targetChild.getProperties());
							if (isPrimaryKey != null && isPrimaryKey) {
								primaryKey = targetChild;
								break;
							}
						}
						if (primaryKey != null) {
							be.nabu.libs.types.api.Element<?> cloned = TypeBaseUtils.clone(primaryKey, structure);
							// optional
							cloned.setProperty(new ValueImpl<Integer>(MinOccursProperty.getInstance(), 0));
							cloned.setProperty(new ValueImpl<Integer>(MaxOccursProperty.getInstance(), ValueUtils.getValue(MaxOccursProperty.getInstance(), navigation.getElement().getProperties())));
							cloned.setProperty(new ValueImpl<String>(NameProperty.getInstance(), navigation.getElement().getName() + "@odata.id"));
							cloned.setProperty(new ValueImpl<String>(ForeignKeyProperty.getInstance(), ((DefinedType) navigation.getElement().getType()).getId() + ":" + primaryKey.getName()));
							cloned.setProperty(new ValueImpl<String>(ForeignNameProperty.getInstance(), navigation.getElement().getName()));
							// no longer a primary key...
							cloned.setProperty(new ValueImpl<Boolean>(PrimaryKeyProperty.getInstance(), false));
							structure.add(cloned);
						}
					}
				};
				runnables.add(linker);
			}
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
		return runnables;
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
			if (UUID.class.isAssignableFrom(((SimpleType) type).getInstanceClass())) {
				values.add(new ValueImpl<UUIDFormat>(UUIDFormatProperty.getInstance(), UUIDFormat.DASHES));
			}
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
			// not entirely clear
			else if ("Edm.Stream".equals(name)) {
				wrapper = InputStream.class;
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
			else if ("Edm.TimeOfDay".equals(name)) {
				wrapper = Date.class;
			}
			// Contains a date and time as an offset in minutes from GMT.
			else if ("Edm.DateTimeOffset".equals(name)) {
				wrapper = Date.class;
//				wrapper = Integer.class;
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

	public List<ODataExpansion> getExpansions() {
		return expansions;
	}

	public void setExpansions(List<ODataExpansion> expansions) {
		this.expansions = expansions;
	}
	
}
