package eu.w4.contrib.bpmnplus.service.fraud;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.rmi.RemoteException;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.script.Bindings;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import javax.script.SimpleBindings;

import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.entity.mime.content.ByteArrayBody;
import org.apache.http.entity.mime.content.StringBody;
import org.apache.http.impl.client.HttpClients;
import org.json.JSONObject;

import eu.w4.common.configuration.Configuration;
import eu.w4.common.configuration.ConfigurationKeyNotFoundException;
import eu.w4.common.exception.CheckedException;
import eu.w4.common.exception.UncheckedException;
import eu.w4.common.log.Logger;
import eu.w4.common.log.LoggerFactory;
import eu.w4.engine.client.bpmn.w4.runtime.ActivityInstance;
import eu.w4.engine.client.bpmn.w4.runtime.ActivityInstanceAttachment;
import eu.w4.engine.client.bpmn.w4.runtime.DataEntry;
import eu.w4.engine.client.bpmn.w4.runtime.ProcessInstanceAttachment;
import eu.w4.engine.client.eci.ContentPart;
import eu.w4.engine.client.eci.Document;
import eu.w4.engine.client.eci.Folder;
import eu.w4.engine.client.eci.Item;
import eu.w4.engine.client.eci.ItemAttachment;
import eu.w4.engine.client.eci.ObjectDefinitionIdentifier;
import eu.w4.engine.client.eci.service.EciContentService;
import eu.w4.engine.client.service.EngineService;
import eu.w4.engine.core.bpmn.service.AbstractService;
import eu.w4.engine.core.bpmn.service.ActivityInstanceAction;
import eu.w4.engine.core.bpmn.service.ConfigurationFileNames;
import eu.w4.engine.core.bpmn.service.DefaultActivityInstanceResult;
import eu.w4.engine.core.bpmn.service.ExecutionContext;
import eu.w4.engine.core.bpmn.service.Name;
import eu.w4.engine.core.bpmn.service.Result;
import eu.w4.engine.core.bpmn.service.Scope;
import eu.w4.engine.core.bpmn.service.Version;

@Name("FraudDetectionSaaS")
@Version("1.0")
@ConfigurationFileNames({ "fraud" })
public class FraudService extends AbstractService
{
  public static final String ECI_TYPE_LANGUAGE = "http://www.w4.eu/spec/BPMN/20110701/ECI";
  public static final String SCRIPT_ENGINE = "javascript";

  private static Logger _logger = LoggerFactory.getLogger(FraudService.class.getName());

  private static final Object _staticMonitor = new Object();
  private static ProcessInstanceAttachment _processInstanceAttachment;
  private static ActivityInstanceAttachment _activityInstanceAttachment;
  private static ItemAttachment _itemAttachment;
  private static EngineService _engineService;
  private static EciContentService _eciContentService;
  private static ScriptEngine _scriptEngine;

  private ExecutionContext _executionContext;
  private Configuration _configuration;

  private Principal _principal;

  private String _subscriptionKey;

  private HttpClient _client;

  private String _bpmnError;

  @Override
  public void afterInit(Scope scope, ExecutionContext executionContext)
      throws CheckedException, RemoteException
  {
    super.afterInit(scope, executionContext);
    _executionContext = executionContext;
    _configuration = getConfiguration();
    _principal = _executionContext.getPrincipal();

    staticInit();

    _subscriptionKey = _configuration.getValue("subscriptionKey");

    _client = HttpClients.createDefault();

    try
    {
      _bpmnError = _configuration.getValue("bpmnError");
    }
    catch (ConfigurationKeyNotFoundException e)
    {
      _bpmnError = null;
    }
    _logger.debug("Fraud service initialized");
  }

  private void staticInit() throws CheckedException, RemoteException
  {
    synchronized (_staticMonitor)
    {
      if (_engineService == null)
      {
        _engineService = _executionContext.getEngineService();
      }

      if (_eciContentService == null)
      {
        _eciContentService = _engineService.getEciContentService();
      }

      if (_processInstanceAttachment == null)
      {
        _processInstanceAttachment = createEmptyProcessInstanceAttachment();
        _processInstanceAttachment.setProcessDescriptorAttached(true);
      }

      if (_activityInstanceAttachment == null)
      {
        _activityInstanceAttachment = createEmptyActivityInstanceAttachment();
        _activityInstanceAttachment.setProcessInstanceAttached(true);
        _activityInstanceAttachment.setProcessInstanceAttachment(_processInstanceAttachment);
        _activityInstanceAttachment.setDataEntriesAttached(true);
        _activityInstanceAttachment.setActivityDescriptorAttached(true);
      }

      if (_itemAttachment == null)
      {
        _itemAttachment = _engineService.getEciObjectFactory().newItemAttachment();
        _itemAttachment.setObjectDefinitionIdentifiersAttached(true);
      }

      if (_scriptEngine == null)
      {
        final ScriptEngineManager scriptEngineManager = new ScriptEngineManager();
        _scriptEngine = scriptEngineManager.getEngineByName(SCRIPT_ENGINE);
      }
    }
  }

  @Override
  public ProcessInstanceAttachment getProcessInstanceAttachment()
      throws CheckedException, RemoteException
  {
    return _processInstanceAttachment;
  }

  @Override
  public ActivityInstanceAttachment getActivityInstanceAttachment()
      throws CheckedException, RemoteException
  {
    return _activityInstanceAttachment;
  }

  static String getExtension(final String filename)
  {
    return filename.substring(filename.length() - 3);
  }

  private static byte[] readAll(InputStream inputStream) throws IOException
  {
    try
    {
      ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
      byte[] buffer = new byte[2048];
      int read = 0;
      while (read >= 0)
      {
        if (read > 0)
        {
          outputStream.write(buffer, 0, read);
        }
        read = inputStream.read(buffer);
      }
      return outputStream.toByteArray();
    }
    finally
    {
      inputStream.close();
    }
  }

  private List<String> toIds(Collection<ObjectDefinitionIdentifier> definitionIdentifiers)
  {
    final List<String> definitionIds = new ArrayList<String>();
    for (final ObjectDefinitionIdentifier currentDefinitionIdentifier : definitionIdentifiers)
    {
      definitionIds.add(currentDefinitionIdentifier.getId());
    }
    return definitionIds;
  }

  static <T> List<T> merge(final List<T>... input)
  {
    final List<T> output = new ArrayList<T>();
    for (final List<T> inputPart : input)
    {
      output.addAll(inputPart);
    }
    return output;
  }

  static List<String> compose(final Object... input)
  {
    if (input.length == 0)
    {
      return Collections.emptyList();
    }

    final List<Object[]> expanded = new ArrayList<Object[]>();
    expanded.add(input);
    for (int i = 0 ; i < expanded.size(); i++)
    {
      Object[] parts = expanded.get(i);
      for (int j = 0; j < parts.length ; j++)
      {
        final Object part = parts[j];
        if (part instanceof Collection)
        {
          final Collection<Object> items = (Collection) part;
          final List<Object[]> composedParts = new ArrayList<Object[]>(items.size());
          for (final Object item : items)
          {
            final Object[] newParts = new Object[parts.length];
            System.arraycopy(parts, 0, newParts, 0, parts.length);
            newParts[j] = item;
            composedParts.add(newParts);
          }
          expanded.remove(i);
          expanded.addAll(i, composedParts);
          i--;
          break;
        }
      }
    }

    final List<String> composedKeys = new ArrayList<String>(expanded.size());
    for (final Object[] parts : expanded)
    {
      final StringBuilder composedKey = new StringBuilder();
      for (Object part : parts)
      {
        if (part != null)
        {
          composedKey.append(part.toString());
        }
      }
      composedKeys.add(composedKey.toString());
    }
    return composedKeys;
  }

  private String readOneConfigurationValue(final List<String> keys, final String defaultValue)
  {
    for (final String key : keys)
    {
      try
      {
        return _configuration.getValue(key);
      }
      catch (ConfigurationKeyNotFoundException e)
      {
        continue;
      }
    }
    return defaultValue;
  }

  private Map<String, String> readAllConfigurationValues(final List<String> prefixes)
  {
    final Map<String, String> values = new HashMap<String, String>();
    keyLoop: for (final String key : _configuration.getKeys())
    {
      for (final String prefix : prefixes)
      {
        if (key.startsWith(prefix))
        {
          try
          {
            final String actualKey = key.substring(prefix.length());
            if (!values.containsKey(actualKey))
            {
              values.put(actualKey, _configuration.getValue(key));
            }
          }
          catch (ConfigurationKeyNotFoundException e)
          {
            throw new UncheckedException("Should not happen: a key listed by configuration is not present in configuration");
          }
          continue keyLoop;
        }
      }
    }
    _logger.debug("Configuration bundle for prefixes [" + prefixes + "] is [" + values + "]");
    return values;
  }

  private FraudResult sendToSaaS(final String checkAlgorithm, final String filename, final byte[] content) throws CheckedException, RemoteException
  {
    final String fileExtension = getExtension(filename);

    final String filePartName = "image1" + fileExtension;

    final JSONObject metadata = new JSONObject();
    metadata.put("IMAGE_RECTO", filePartName);

    final JSONObject parameters = new JSONObject();
    parameters.put("metadata", metadata);

    final String url = "https://itesoftfrauddev.azure-api.net/checkdocument/" + checkAlgorithm;
    final HttpPost postRib = new HttpPost(url);
    postRib.addHeader("Ocp-Apim-Subscription-Key", _subscriptionKey);
    postRib.setEntity(MultipartEntityBuilder.create()
                      .addPart("Parameters", new StringBody(parameters.toString(), ContentType.APPLICATION_JSON))
                      .addPart(filePartName, new ByteArrayBody(content, ContentType.create("image/" + fileExtension), filename))
                      .build());

    final JSONObject response;
    try
    {
      final HttpResponse httpResponse = _client.execute(postRib);
      final String responseStr = new String(readAll(httpResponse.getEntity().getContent()));
      if (_logger.isDebugEnabled())
      {
        _logger.debug("HTTP call to Fraud detection SaaS on [" + url + "] and parameters [" + parameters + "] "
                    + "resulted in [" + httpResponse.getStatusLine() + "] with content [" + responseStr + "]");
      }
      response = new JSONObject(responseStr);
    }
    catch (final IOException e)
    {
      throw new CheckedException("Error in HTTP call", e);
    }
    final FraudResult result = new FraudResult();
    result.setValid(response.getBoolean("result"));
    result.setStatus("SUCCESS".equals(response.getString("status").toUpperCase()));
    result.setStatusText(response.getString("status").toUpperCase());

    final JSONObject details = response.getJSONObject("details");
    if (details != null)
    {
      for (final String detailKey : details.keySet())
      {
        final JSONObject detail = details.getJSONObject(detailKey);
        final FraudDetail fraudDetail = new FraudDetail();
        fraudDetail.setStatus("SUCCESS".equals(detail.getString("status").toUpperCase()));
        fraudDetail.setStatusText(detail.getString("status").toUpperCase());
        fraudDetail.setDescription(detail.getString("description"));
        fraudDetail.setName(detailKey.toUpperCase());
        result.getDetails().add(fraudDetail);
      }
    }
    return result;
  }

  private FraudResult sendToSaaS(final String checkAlgorithm, final Document eciDocument) throws CheckedException, RemoteException
  {
    final List<ContentPart> contentParts = _eciContentService.getDocumentContentParts(_principal, eciDocument.getIdentifier());
    final ContentPart contentPart = contentParts.get(0); //$$$$ for now, only compatible with mono-doc

    if (_logger.isDebugEnabled())
    {
      _logger.debug("Sending document [" + eciDocument.getName() + "] with id [" + eciDocument.getIdentifier().getId() + "] " +
                    "with type [" + eciDocument.getObjectDefinitionIdentifier().getId() + "] of size [" + contentPart.getContent().length + "] bytes " +
                    "to Fraud Detection SaaS with algorithm [" + checkAlgorithm + "]");
    }

    return sendToSaaS(checkAlgorithm, contentPart.getName(), contentPart.getContent());
  }

  private FraudResult sendToSaaS(final String checkAlgorithm, final Item eciItem) throws CheckedException, RemoteException
  {
    if (eciItem == null)
    {
      FraudResult result = new FraudResult();
      result.setStatus(false);
      result.setValid(false);
      return result;
    }

    if (eciItem instanceof Folder)
    {
      final Collection<Item> children = _eciContentService.getChildItems(_principal, eciItem.getIdentifier(), _itemAttachment);
      if (children.isEmpty())
      {
        FraudResult result = new FraudResult();
        result.setStatus(false);
        result.setValid(false);
        return result;
      }
      else
      {
        // $$$$ for now only takes the first one
        final Item child = children.iterator().next();
        return sendToSaaS(checkAlgorithm, child);
      }
    }
    else
    {
      return sendToSaaS(checkAlgorithm, (Document) eciItem);
    }
  }

  private boolean processDetailEntity(final Item master, final Item detail) throws CheckedException, RemoteException
  {
    final List<String> detailDefinitionIds = toIds(detail.getObjectDefinitionIdentifiers());
    final List<String> masterDefinitionIds = toIds(master.getObjectDefinitionIdentifiers());

    if (_logger.isDebugEnabled())
    {
      _logger.debug("Processing detail entity [" + detail.getName() + "] with id [" + detail.getIdentifier().getId() + "] " +
                    "with type [" + detail.getObjectDefinitionIdentifier().getId() + "] for master [" + master.getIdentifier().getId() + "]");
    }

    final String checkAlgorithm = readOneConfigurationValue(compose("detail.", detailDefinitionIds, ".algorithm"), "ALL");

    FraudResult result = sendToSaaS(checkAlgorithm, detail);

    final Map<String, String> mappings = readAllConfigurationValues(merge(compose("mapping.master.", masterDefinitionIds, ".detail.", detailDefinitionIds, "."),
                                                                          compose("mapping.master.", masterDefinitionIds, ".detail.*."),
                                                                          compose("mapping.detail.", detailDefinitionIds, "."),
                                                                          Arrays.asList("mapping.detail.*.")));

    final Bindings scriptBindings = new SimpleBindings();
    scriptBindings.put("result", result);
    final Map<String, Object> properties = new HashMap<String, Object>();
    for (final Map.Entry<String, String> mapping : mappings.entrySet())
    {
      try
      {
        final Object value = _scriptEngine.eval(mapping.getValue(), scriptBindings);
        if (value != null)
        {
          properties.put(mapping.getKey(), value);
        }
      }
      catch (final ScriptException e)
      {
        _logger.error("Cannot map property [" + mapping.getKey() + "] if type [" + detail.getObjectDefinitionIdentifier().getId() + "] with script [" + mapping.getValue() + "] " +
                      "exception [" + e.getClass().getName() + "] with message [" +  e.getMessage() + "] encountered");
      }
    }
    if (_logger.isDebugEnabled())
    {
      _logger.debug("Mapping for detail entity [" + detail.getIdentifier().getId() + "] is [ " + properties + "]");
    }
    if (properties.size() > 0)
    {
      _eciContentService.modifyItemProperties(_principal, null, detail.getIdentifier(), properties);
    }

    return result.isValid();
  }

  /**
   * Find detail entities to process in the tree
   */
  private boolean processTree(final Item master, final Item current, final List<String> detailEntityDefinitionIds) throws CheckedException, RemoteException
  {
    boolean result = true;
    final List<ObjectDefinitionIdentifier> currentDefinitionIdentifiers = new ArrayList<ObjectDefinitionIdentifier>(current.getObjectDefinitionIdentifiers());
    final List<String> currentDefinitionIds = new ArrayList<String>();
    for (final ObjectDefinitionIdentifier currentDefinitionIdentifier : currentDefinitionIdentifiers)
    {
      currentDefinitionIds.add(currentDefinitionIdentifier.getId());
    }
    currentDefinitionIds.retainAll(detailEntityDefinitionIds);
    if (!currentDefinitionIds.isEmpty())
    {
      result &= processDetailEntity(master, current);
    }
    else
    {
      if (current instanceof Folder)
      {
        final Collection<Item> children = _eciContentService.getChildItems(_principal, current.getIdentifier(), _itemAttachment);
        for (final Item child : children)
        {
          result &= processTree(master, child, detailEntityDefinitionIds);
        }
      }
    }
    return result;
  }

  private boolean processMasterEntity(final Item master) throws CheckedException, RemoteException
  {
    final Item fullMaster = _eciContentService.getItem(_principal, master.getIdentifier(), _itemAttachment);

    if (_logger.isDebugEnabled())
    {
      _logger.debug("Processing master entity [" + master.getName() + "] with id [" + master.getIdentifier().getId() + "] " +
                    "with type [" + master.getObjectDefinitionIdentifier().getId() + "]");
    }

    if (fullMaster instanceof Document)
    {
      return processDetailEntity(fullMaster, fullMaster);
    }
    else
    {
      final List<ObjectDefinitionIdentifier> definitionIdentifiers = new ArrayList<ObjectDefinitionIdentifier>(master.getObjectDefinitionIdentifiers());
      final String detailEntityDefinitionIdentifier = readOneConfigurationValue(compose("master.", toIds(definitionIdentifiers), ".details"), null);
      if (detailEntityDefinitionIdentifier == null)
      {
        _logger.debug("No detail entity definition for [" + fullMaster.getObjectDefinitionIdentifier().getId() + "], using item [" + fullMaster.getName() + "] itself as the detail");
        return processDetailEntity(fullMaster, fullMaster);
      }
      else
      {
        final List<String> detailEntityDefinitionIds = Arrays.asList(detailEntityDefinitionIdentifier.split(","));
        return processTree(fullMaster, fullMaster, detailEntityDefinitionIds);
      }
    }
  }

  @Override
  public Result execute() throws CheckedException, RemoteException
  {
    final ActivityInstance activityInstance = getActivityInstance();
    final Map<String, DataEntry> dataEntries = activityInstance.getDataEntries();

    boolean result = true;
    for (final DataEntry dataEntry : dataEntries.values())
    {
      if (!ECI_TYPE_LANGUAGE.equals(dataEntry.getTypeLanguage()))
      {
        continue;
      }

      final Item eciItem = (Item) dataEntry.getValue();

      result &= processMasterEntity(eciItem);
    }

    if (_bpmnError != null && !_bpmnError.isEmpty() && !result)
    {
      return new DefaultActivityInstanceResult(ActivityInstanceAction.FAIL, _bpmnError);
    }

    return new DefaultActivityInstanceResult(ActivityInstanceAction.COMPLETE);
  }
}
