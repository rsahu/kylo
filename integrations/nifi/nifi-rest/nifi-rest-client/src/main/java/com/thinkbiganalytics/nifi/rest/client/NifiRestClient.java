package com.thinkbiganalytics.nifi.rest.client;

import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.thinkbiganalytics.nifi.feedmgr.ConfigurationPropertyReplacer;
import com.thinkbiganalytics.nifi.feedmgr.CreateFeedBuilder;
import com.thinkbiganalytics.nifi.feedmgr.NifiEnvironmentProperties;
import com.thinkbiganalytics.nifi.feedmgr.TemplateCreationHelper;
import com.thinkbiganalytics.nifi.feedmgr.TemplateInstanceCreator;
import com.thinkbiganalytics.nifi.rest.model.NifiProcessGroup;
import com.thinkbiganalytics.nifi.rest.model.NifiProcessorSchedule;
import com.thinkbiganalytics.nifi.rest.model.NifiProperty;
import com.thinkbiganalytics.nifi.rest.model.flow.NifiFlowDeserializer;
import com.thinkbiganalytics.nifi.rest.model.flow.NifiFlowProcessGroup;
import com.thinkbiganalytics.nifi.rest.model.visitor.NifiFlowBuilder;
import com.thinkbiganalytics.nifi.rest.model.visitor.NifiVisitableProcessGroup;
import com.thinkbiganalytics.nifi.rest.model.visitor.NifiVisitableProcessor;
import com.thinkbiganalytics.nifi.rest.support.NifiConnectionUtil;
import com.thinkbiganalytics.nifi.rest.support.NifiConstants;
import com.thinkbiganalytics.nifi.rest.support.NifiProcessUtil;
import com.thinkbiganalytics.nifi.rest.support.NifiPropertyUtil;
import com.thinkbiganalytics.nifi.rest.support.NifiTemplateUtil;
import com.thinkbiganalytics.nifi.rest.visitor.NifiConnectionOrderVisitor;
import com.thinkbiganalytics.rest.JerseyRestClient;
import com.thinkbiganalytics.support.FeedNameUtil;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.nifi.web.api.dto.ConnectableDTO;
import org.apache.nifi.web.api.dto.ConnectionDTO;
import org.apache.nifi.web.api.dto.ControllerServiceDTO;
import org.apache.nifi.web.api.dto.PortDTO;
import org.apache.nifi.web.api.dto.ProcessGroupDTO;
import org.apache.nifi.web.api.dto.ProcessorDTO;
import org.apache.nifi.web.api.dto.TemplateDTO;
import org.apache.nifi.web.api.dto.search.ComponentSearchResultDTO;
import org.apache.nifi.web.api.entity.AboutEntity;
import org.apache.nifi.web.api.entity.BulletinBoardEntity;
import org.apache.nifi.web.api.entity.ConnectionEntity;
import org.apache.nifi.web.api.entity.ConnectionsEntity;
import org.apache.nifi.web.api.entity.ControllerServiceEntity;
import org.apache.nifi.web.api.entity.ControllerServiceTypesEntity;
import org.apache.nifi.web.api.entity.ControllerServicesEntity;
import org.apache.nifi.web.api.entity.ControllerStatusEntity;
import org.apache.nifi.web.api.entity.DropRequestEntity;
import org.apache.nifi.web.api.entity.Entity;
import org.apache.nifi.web.api.entity.FlowSnippetEntity;
import org.apache.nifi.web.api.entity.InputPortEntity;
import org.apache.nifi.web.api.entity.InputPortsEntity;
import org.apache.nifi.web.api.entity.LineageEntity;
import org.apache.nifi.web.api.entity.ListingRequestEntity;
import org.apache.nifi.web.api.entity.OutputPortEntity;
import org.apache.nifi.web.api.entity.OutputPortsEntity;
import org.apache.nifi.web.api.entity.ProcessGroupEntity;
import org.apache.nifi.web.api.entity.ProcessGroupsEntity;
import org.apache.nifi.web.api.entity.ProcessorEntity;
import org.apache.nifi.web.api.entity.ProvenanceEntity;
import org.apache.nifi.web.api.entity.ProvenanceEventEntity;
import org.apache.nifi.web.api.entity.SearchResultsEntity;
import org.apache.nifi.web.api.entity.TemplateEntity;
import org.apache.nifi.web.api.entity.TemplatesEntity;
import org.glassfish.jersey.media.multipart.MultiPart;
import org.glassfish.jersey.media.multipart.file.FileDataBodyPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.ws.rs.ClientErrorException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Form;
import javax.ws.rs.core.MediaType;

/**
 * Created by sr186054 on 1/9/16.
 *
 * @TODO break up into separate modules for the various units of work (i.e. TemplateRestClient, FeedRestClient,  or out into separate Working classes to make this more readable
 */
public class NifiRestClient extends JerseyRestClient implements NifiFlowVisitorClient {

    private static final Logger log = LoggerFactory.getLogger(NifiRestClient.class);

    private String apiPath = "/nifi-api";

    private NifiRestClientConfig clientConfig;

    public NifiRestClient(NifiRestClientConfig config) {
        super(config);
        this.clientConfig = config;

    }

    protected WebTarget getBaseTarget() {
        WebTarget target = super.getBaseTarget();
        return target.path(apiPath);
    }

    public String getClusterType() {
        return clientConfig.getClusterType();
    }


    /**
     * Gets Template data, either a quick view or including all its content
     */
    public TemplatesEntity getTemplates(boolean includeFlow) {

        TemplatesEntity nifiTemplatesEntity = get("/controller/templates", null, TemplatesEntity.class);

        //get the contents and update the returned DTO with the populated snippetDTO
        for (TemplateDTO dto : ImmutableSet.copyOf(nifiTemplatesEntity.getTemplates())) {
            if (includeFlow) {
                nifiTemplatesEntity.getTemplates().remove(dto);
                TemplateDTO populatedDto = populateTemplateDTO(dto);
                nifiTemplatesEntity.getTemplates().add(populatedDto);
            }
        }
        return nifiTemplatesEntity;

    }

    public TemplateEntity deleteTemplate(String templateId) {
        return delete("/controller/templates/" + templateId, null, TemplateEntity.class);
    }


    public TemplateDTO importTemplate(String templateXml) throws IOException {
        return importTemplate(null, templateXml);
    }

    public TemplateDTO importTemplate(String templateName, String templateXml) throws IOException {
        if (templateName == null) {
            templateName = "import_template_" + System.currentTimeMillis();
        }

        MultiPart multiPart = new MultiPart();
        multiPart.setMediaType(MediaType.MULTIPART_FORM_DATA_TYPE);
        File tmpFile = File.createTempFile(templateName, ".xml");
        FileUtils.writeStringToFile(tmpFile, templateXml);

        FileDataBodyPart fileDataBodyPart = new FileDataBodyPart("template", tmpFile,
                                                                 MediaType.APPLICATION_OCTET_STREAM_TYPE);
        multiPart.bodyPart(fileDataBodyPart);
        multiPart.setMediaType(MediaType.MULTIPART_FORM_DATA_TYPE);

        TemplateEntity templateEntity = postMultiPart("/controller/templates", multiPart, TemplateEntity.class);
        if (templateEntity != null) {
            return templateEntity.getTemplate();
        }
        return null;
    }

    /**
     * Populate a Template with the contents of its Flow
     */
    private TemplateDTO populateTemplateDTO(TemplateDTO dto) {
        if (dto.getSnippet() == null) {
            TemplateDTO populatedDto = getTemplateById(dto.getId());
            populatedDto.setId(dto.getId());
            populatedDto.setUri(dto.getUri());
            populatedDto.setDescription(dto.getDescription());
            return populatedDto;
        } else {
            return dto;
        }
    }


    /**
     * return the Template as an XML string
     */
    public String getTemplateXml(String templateId) throws NifiComponentNotFoundException {
        try {
            String xml = get("/controller/templates/" + templateId, null, String.class);
            return xml;
        } catch (NotFoundException e) {
            throw new NifiComponentNotFoundException(templateId, NifiConstants.NIFI_COMPONENT_TYPE.TEMPLATE, e);
        }
    }


    /**
     * Return a template, populated along with its Flow snippet
     */
    public TemplateDTO getTemplateById(String templateId) throws NifiComponentNotFoundException {
        try {
            TemplateDTO dto = get("/controller/templates/" + templateId, null, TemplateDTO.class);
            return dto;
        } catch (NotFoundException e) {
            throw new NifiComponentNotFoundException(templateId, NifiConstants.NIFI_COMPONENT_TYPE.TEMPLATE, e);
        }
    }

    public List<TemplateDTO> getTemplatesMatchingInputPortName(final String inputPortName) {
        TemplatesEntity entity = getTemplates(true);
        if(entity != null) {

          return  Lists.newArrayList(Iterables.filter(entity.getTemplates(), new Predicate<TemplateDTO>() {
                @Override
                public boolean apply(TemplateDTO templateDTO) {
                    PortDTO match = Iterables.tryFind(templateDTO.getSnippet().getInputPorts(), new Predicate<PortDTO>() {
                        @Override
                        public boolean apply(PortDTO o) {
                            return o.getName().equalsIgnoreCase(inputPortName);
                        }
                    }).orNull();
                    return (match != null);
                }
            }));

        }
        return null;
    }

    /**
     * Returns a Map of the Template Name and the Template XML that has a matching Input Port in the template.
     * @param inputPortName
     * @return
     */
    public Map<String,String> getTemplatesAsXmlMatchingInputPortName(final String inputPortName) {
        Map<String,String> map = new HashMap<>();
        List<TemplateDTO> matchingTemplates = getTemplatesMatchingInputPortName(inputPortName);
        if(matchingTemplates != null){

         for(TemplateDTO templateDTO : matchingTemplates){
             if(!map.containsKey(templateDTO.getName())){
              String templateXml =  getTemplateXml(templateDTO.getId());
                 map.put(templateDTO.getName(),templateXml);
             }
         }
        }
        return map;
    }

    /**
     * return a template by Name, populated with its Flow snippet If not found it returns null
     */
    public TemplateDTO getTemplateByName(String templateName) {
        TemplatesEntity templatesEntity = getTemplates(false);
        TemplateDTO templateDTO = null;
        if (templatesEntity.getTemplates() != null && !templatesEntity.getTemplates().isEmpty()) {
            for (TemplateDTO dto : templatesEntity.getTemplates()) {
                if (dto.getName().equalsIgnoreCase(templateName)) {
                    templateDTO = populateTemplateDTO(dto);
                    break;
                }
            }
        }

        return templateDTO;
    }

    public FlowSnippetEntity instantiateFlowFromTemplate(String processGroupId, String templateId) throws NifiComponentNotFoundException {
        try {
            Entity status = getControllerRevision();
            String clientId = status.getRevision().getClientId();
            String originX = "10";
            String originY = "10";
            Form form = new Form();
            form.param("templateId", templateId);
            form.param("clientId", clientId);
            form.param("originX", originX);
            form.param("originY", originY);
            form.param("version", status.getRevision().getVersion().toString());
            FlowSnippetEntity
                response =
                postForm("/controller/process-groups/" + processGroupId + "/template-instance", form, FlowSnippetEntity.class);
            return response;
        } catch (NotFoundException e) {
            throw new NifiComponentNotFoundException(
                "Unable to create Template instance for templateId: " + templateId + " under Process group " + processGroupId + ".  Unable find the processGroup or template");
        } catch (ClientErrorException e) {
            final String msg = e.getResponse().readEntity(String.class);
            throw new NifiComponentNotFoundException("Unable to create Template instance for templateId: " + templateId + " under Process group " + processGroupId + ". " + msg);
        }
    }

    public NifiProcessGroup createNewTemplateInstance(String templateId, Map<String, Object> staticConfigProperties, boolean createReusableFlow) {
        TemplateInstanceCreator creator = new TemplateInstanceCreator(this, templateId, staticConfigProperties, createReusableFlow);
        NifiProcessGroup group = creator.createTemplate();
        return group;
    }

    public void markConnectionPortsAsRunning(ProcessGroupEntity entity) {
        //1 startAll
        try {
            startAll(entity.getProcessGroup().getId(), entity.getProcessGroup().getParentGroupId());
        } catch (NifiClientRuntimeException e) {
            log.error("Error trying to mark connection ports Running for {}", entity.getProcessGroup().getName());
        }

        Set<PortDTO> ports = null;
        try {
            ports = getPortsForProcessGroup(entity.getProcessGroup().getParentGroupId());
        } catch (NifiClientRuntimeException e) {
            log.error("Error getPortsForProcessGroup {}", entity.getProcessGroup().getName());
        }
        if (ports != null && !ports.isEmpty()) {
            for (PortDTO port : ports) {
                port.setState(NifiProcessUtil.PROCESS_STATE.RUNNING.name());
                if (port.getType().equalsIgnoreCase(NifiConstants.NIFI_PORT_TYPE.INPUT_PORT.name())) {
                    try {
                        startInputPort(entity.getProcessGroup().getParentGroupId(), port.getId());
                    } catch (NifiClientRuntimeException e) {
                        log.error("Error starting Input Port {} for process group {}", port.getName(), entity.getProcessGroup().getName());
                    }
                } else if (port.getType().equalsIgnoreCase(NifiConstants.NIFI_PORT_TYPE.OUTPUT_PORT.name())) {
                    try {
                        startOutputPort(entity.getProcessGroup().getParentGroupId(), port.getId());
                    } catch (NifiClientRuntimeException e) {
                        log.error("Error starting Output Port {} for process group {}", port.getName(), entity.getProcessGroup().getName());
                    }
                }
            }

        }

    }

    public ControllerStatusEntity getControllerStatus() {
        return get("/controller/status", null, ControllerStatusEntity.class);
    }

    /**
     * Gets the current Revision and Version of Nifi instance. This is needed when performing an update to pass over the revision.getVersion() for locking purposes
     */
    public Entity getControllerRevision() {
        return get("/controller/revision", null, Entity.class);
    }

    /**
     * Expose all Properties for a given Template as parameters for external use
     */
    public List<NifiProperty> getPropertiesForTemplate(String templateId) {
        TemplateDTO dto = getTemplateById(templateId);
        ProcessGroupEntity rootProcessGroup = getProcessGroup("root", false, false);
        return NifiPropertyUtil.getPropertiesForTemplate(rootProcessGroup.getProcessGroup(), dto);
    }


    public Set<PortDTO> getPortsForTemplate(String templateId) throws NifiComponentNotFoundException {
        Set<PortDTO> ports = new HashSet<>();
        TemplateDTO dto = getTemplateById(templateId);
        Set<PortDTO> inputPorts = dto.getSnippet().getInputPorts();
        if (inputPorts != null) {
            ports.addAll(inputPorts);
        }
        Set<PortDTO> outputPorts = dto.getSnippet().getOutputPorts();
        if (outputPorts != null) {
            ports.addAll(outputPorts);
        }
        return ports;
    }

    public Set<PortDTO> getPortsForProcessGroup(String processGroupId) throws NifiComponentNotFoundException {
        Set<PortDTO> ports = new HashSet<>();
        ProcessGroupEntity processGroupEntity = getProcessGroup(processGroupId, false, true);
        Set<PortDTO> inputPorts = processGroupEntity.getProcessGroup().getContents().getInputPorts();
        if (inputPorts != null) {
            ports.addAll(inputPorts);
        }
        Set<PortDTO> outputPorts = processGroupEntity.getProcessGroup().getContents().getOutputPorts();
        if (outputPorts != null) {
            ports.addAll(outputPorts);
        }
        return ports;
    }

    /**
     * Expose all Properties for a given Template as parameters for external use If template is not found it will return an Empty ArrayList()
     */
    public List<NifiProperty> getPropertiesForTemplateByName(String templateName) {
        TemplateDTO dto = getTemplateByName(templateName);
        ProcessGroupEntity rootProcessGroup = getProcessGroup("root", false, false);
        return NifiPropertyUtil.getPropertiesForTemplate(rootProcessGroup.getProcessGroup(), dto);
    }

    /**
     * Returns an Empty ArrayList of nothing is found
     */
    public List<NifiProperty> getAllProperties() throws NifiComponentNotFoundException {
        ProcessGroupEntity root = getRootProcessGroup();
        return NifiPropertyUtil.getProperties(root.getProcessGroup());
    }


    public List<NifiProperty> getPropertiesForProcessGroup(String processGroupId) throws NifiComponentNotFoundException {
        ProcessGroupEntity processGroup = getProcessGroup(processGroupId, true, true);
        return NifiPropertyUtil.getProperties(processGroup.getProcessGroup());
    }

    private void updateEntityForSave(Entity entity) {
        Entity status = getControllerRevision();
        entity.setRevision(status.getRevision());
    }

    public ProcessGroupEntity createProcessGroup(String name) {

        return createProcessGroup("root", name);
    }

    public ProcessGroupEntity createProcessGroup(String parentGroupId, String name) throws NifiComponentNotFoundException {

        ProcessGroupEntity entity = new ProcessGroupEntity();
        ProcessGroupDTO group = new ProcessGroupDTO();
        group.setName(name);
        updateEntityForSave(entity);
        try {
            entity.setProcessGroup(group);
            ProcessGroupEntity
                returnedGroup =
                post("/controller/process-groups/" + parentGroupId + "/process-group-references", entity, ProcessGroupEntity.class);
            return returnedGroup;
        } catch (NotFoundException e) {
            throw new NifiComponentNotFoundException(parentGroupId, NifiConstants.NIFI_COMPONENT_TYPE.PROCESS_GROUP, e);
        }

    }

    /**
     * //mark everything as running //http://localhost:8079/nifi-api/controller/process-groups/2f0e55cb-34af-4e5b-8bf9-38909ea3af51/processors/bbef9df7-ff67-49fb-aa2e-3200ece92128
     */
    public List<ProcessorDTO> markProcessorsAsRunning(List<ProcessorDTO> processors) {
        Entity status = getControllerRevision();
        List<ProcessorDTO> dtos = new ArrayList<>();
        for (ProcessorDTO dto : processors) {
            if (NifiProcessUtil.PROCESS_STATE.STOPPED.name().equalsIgnoreCase(dto.getState())) {
                //start it
                ProcessorEntity entity = new ProcessorEntity();
                dto.setState(NifiProcessUtil.PROCESS_STATE.RUNNING.name());
                entity.setProcessor(dto);
                entity.setRevision(status.getRevision());

                updateEntityForSave(entity);
                ProcessorEntity
                    processorEntity =
                    put("/controller/process-groups/" + dto.getParentGroupId() + "/processors/" + dto.getId(), entity,
                        ProcessorEntity.class);
                if (processorEntity != null) {
                    dtos.add(processorEntity.getProcessor());
                }
            }
        }
        return dtos;

    }

    public ProcessGroupEntity markProcessorGroupAsRunning(ProcessGroupDTO groupDTO) {
        ProcessGroupEntity entity = new ProcessGroupEntity();
        entity.setProcessGroup(groupDTO);
        entity.getProcessGroup().setRunning(true);
        updateEntityForSave(entity);
        return put("/controller/process-groups/" + groupDTO.getParentGroupId() + "/process-group-references/" + groupDTO.getId(),
                   entity, ProcessGroupEntity.class);


    }


    public NifiProcessGroup createTemplateInstanceAsProcessGroup(String templateId, String category, String feedName,
                                                                 String inputProcessorType, List<NifiProperty> properties,
                                                                 NifiProcessorSchedule feedSchedule) {
        return CreateFeedBuilder.newFeed(this, category, feedName, templateId).inputProcessorType(inputProcessorType)
            .feedSchedule(feedSchedule).properties(properties).build();
    }

    public CreateFeedBuilder newFeedBuilder(String templateId, String category, String feedName) {
        return CreateFeedBuilder.newFeed(this, category, feedName, templateId);
    }


    private Map<String, Object> getUpdateParams() {
        Entity status = getControllerRevision();
        Map<String, Object> params = new HashMap<>();
        params.put("version", status.getRevision().getVersion().toString());
        params.put("clientId", status.getRevision().getClientId());
        return params;
    }


    public void stopAllProcessors(ProcessGroupDTO groupDTO) {
        stopAllProcessors(groupDTO.getId(), groupDTO.getParentGroupId());
    }

    public void stopProcessor(ProcessorDTO processorDTO) {
        if (NifiProcessUtil.PROCESS_STATE.RUNNING.name().equalsIgnoreCase(processorDTO.getState())) {
            stopProcessor(processorDTO.getParentGroupId(), processorDTO.getId());
        }
    }

    public void stopProcessor(String processorGroupId, String processorId) {
        ProcessorEntity entity = new ProcessorEntity();
        ProcessorDTO dto = new ProcessorDTO();
        dto.setId(processorId);
        dto.setParentGroupId(processorGroupId);
        dto.setState(NifiProcessUtil.PROCESS_STATE.STOPPED.name());
        entity.setProcessor(dto);
        updateProcessor(entity);
    }

    public void startProcessor(String processorGroupId, String processorId) {
        ProcessorEntity entity = new ProcessorEntity();
        ProcessorDTO dto = new ProcessorDTO();
        dto.setId(processorId);
        dto.setParentGroupId(processorGroupId);
        dto.setState(NifiProcessUtil.PROCESS_STATE.RUNNING.name());
        entity.setProcessor(dto);
        updateProcessor(entity);
    }

    public void stopInputs(ProcessGroupDTO groupDTO) {
        List<ProcessorDTO> inputs = NifiProcessUtil.getInputProcessors(groupDTO);
        if (inputs != null) {
            for (ProcessorDTO input : inputs) {
                stopProcessor(input);
            }
        }
        InputPortsEntity inputPorts = getInputPorts(groupDTO.getId());
        if (inputPorts != null) {
            for (PortDTO port : inputPorts.getInputPorts()) {
                stopInputPort(groupDTO.getId(), port.getId());
            }
        }
    }

    public ProcessGroupEntity stopInputs(String processGroupId) {
        ProcessGroupEntity entity = getProcessGroup(processGroupId, false, true);
        if (entity != null && entity.getProcessGroup() != null) {
            stopInputs(entity.getProcessGroup());
            return entity;
        }
        return null;
    }

    public ProcessGroupEntity stopAllProcessors(String processGroupId, String parentProcessGroupId) throws NifiClientRuntimeException {
        ProcessGroupEntity entity = getProcessGroup(processGroupId, false, false);
        entity.getProcessGroup().setRunning(false);
        updateEntityForSave(entity);
        return put("/controller/process-groups/" + parentProcessGroupId + "/process-group-references/" + processGroupId,
                   entity, ProcessGroupEntity.class);
    }


    public ProcessGroupEntity startAll(String processGroupId, String parentProcessGroupId) throws NifiClientRuntimeException {
        ProcessGroupEntity entity = getProcessGroup(processGroupId, false, false);
        entity.getProcessGroup().setRunning(true);
        updateEntityForSave(entity);
        return put("/controller/process-groups/" + parentProcessGroupId + "/process-group-references/" + processGroupId,
                   entity, ProcessGroupEntity.class);
    }

    public InputPortEntity stopInputPort(String groupId, String portId) throws NifiClientRuntimeException {
        InputPortEntity entity = new InputPortEntity();
        PortDTO portDTO = new PortDTO();
        portDTO.setId(portId);
        portDTO.setState(NifiProcessUtil.PROCESS_STATE.STOPPED.name());
        entity.setInputPort(portDTO);
        updateEntityForSave(entity);
        return put("/controller/process-groups/" + groupId + "/input-ports/" + portId, entity, InputPortEntity.class);
    }

    public OutputPortEntity stopOutputPort(String groupId, String portId) throws NifiClientRuntimeException {
        OutputPortEntity entity = new OutputPortEntity();
        PortDTO portDTO = new PortDTO();
        portDTO.setId(portId);
        portDTO.setState(NifiProcessUtil.PROCESS_STATE.STOPPED.name());
        entity.setOutputPort(portDTO);
        updateEntityForSave(entity);
        return put("/controller/process-groups/" + groupId + "/output-ports/" + portId, entity, OutputPortEntity.class);
    }

    public InputPortEntity startInputPort(String groupId, String portId) throws NifiClientRuntimeException {
        InputPortEntity entity = new InputPortEntity();
        PortDTO portDTO = new PortDTO();
        portDTO.setId(portId);
        portDTO.setState(NifiProcessUtil.PROCESS_STATE.RUNNING.name());
        entity.setInputPort(portDTO);
        updateEntityForSave(entity);
        return put("/controller/process-groups/" + groupId + "/input-ports/" + portId, entity, InputPortEntity.class);
    }

    public OutputPortEntity startOutputPort(String groupId, String portId) throws NifiClientRuntimeException {
        OutputPortEntity entity = new OutputPortEntity();
        PortDTO portDTO = new PortDTO();
        portDTO.setId(portId);
        portDTO.setState(NifiProcessUtil.PROCESS_STATE.RUNNING.name());
        entity.setOutputPort(portDTO);
        updateEntityForSave(entity);
        return put("/controller/process-groups/" + groupId + "/output-ports/" + portId, entity, OutputPortEntity.class);
    }

    public ProcessGroupEntity deleteProcessGroup(ProcessGroupDTO groupDTO) throws NifiClientRuntimeException {
        return deleteProcessGroup(groupDTO, null);
    }

    private ProcessGroupEntity deleteProcessGroup(ProcessGroupDTO groupDTO, Integer retryAttempt) throws NifiClientRuntimeException {

        if (retryAttempt == null) {
            retryAttempt = 0;
        }
        ProcessGroupEntity entity = stopProcessGroup(groupDTO);
        try {
            entity = doDeleteProcessGroup(entity.getProcessGroup());

        } catch (WebApplicationException e) {
            NifiClientRuntimeException clientException = new NifiClientRuntimeException(e);
            if (clientException.is409Error() && retryAttempt < 2) {
                //wait and retry?
                retryAttempt++;
                try {
                    Thread.sleep(300);
                    deleteProcessGroup(groupDTO, retryAttempt);
                } catch (InterruptedException e2) {
                    throw new NifiClientRuntimeException("Unable to delete Process Group " + groupDTO.getName(), e2);
                }
            } else {
                throw clientException;
            }
        }
        return entity;


    }

    public ProcessGroupEntity stopProcessGroup(ProcessGroupDTO groupDTO) throws NifiClientRuntimeException {
        ProcessGroupEntity entity = new ProcessGroupEntity();
        entity.setProcessGroup(groupDTO);
        entity.getProcessGroup().setRunning(false);
        entity.getProcessGroup().setRunning(false);
        updateEntityForSave(entity);
        return put("/controller/process-groups/" + groupDTO.getParentGroupId() + "/process-group-references/" + groupDTO.getId(),
                   entity, ProcessGroupEntity.class);
    }

    private ProcessGroupEntity doDeleteProcessGroup(ProcessGroupDTO groupDTO) throws NifiClientRuntimeException {
        Entity status = getControllerRevision();
        Map<String, Object> params = getUpdateParams();
        ProcessGroupEntity
            entity =
            delete("/controller/process-groups/" + groupDTO.getParentGroupId() + "/process-group-references/" + groupDTO.getId(),
                   params, ProcessGroupEntity.class);
        return entity;

    }


    public List<ProcessGroupEntity> deleteChildProcessGroups(String processGroupId) throws NifiClientRuntimeException {
        List<ProcessGroupEntity> deletedEntities = new ArrayList<>();
        ProcessGroupEntity entity = getProcessGroup(processGroupId, true, true);
        if (entity != null && entity.getProcessGroup().getContents().getProcessGroups() != null) {
            for (ProcessGroupDTO groupDTO : entity.getProcessGroup().getContents().getProcessGroups()) {
                deletedEntities.add(deleteProcessGroup(groupDTO));
            }
        }
        return deletedEntities;

    }

    public ProcessGroupEntity deleteProcessGroup(String processGroupId) throws NifiClientRuntimeException {
        ProcessGroupEntity entity = getProcessGroup(processGroupId, false, true);
        ProcessGroupEntity deletedEntity = null;
        if (entity != null && entity.getProcessGroup() != null) {
            deletedEntity = deleteProcessGroup(entity.getProcessGroup());
        }

        return deletedEntity;
    }

    /**
     * Deletes the specified process group and any matching connections.
     *
     * @param processGroup the process group to be deleted
     * @param connections the list of all connections from the parent process group
     * @return the deleted process group
     * @throws NifiClientRuntimeException if the process group could not be deleted
     * @throws NifiComponentNotFoundException if the process group does not exist
     */
    public ProcessGroupEntity deleteProcessGroupAndConnections(@Nonnull final ProcessGroupDTO processGroup, @Nonnull final Set<ConnectionDTO> connections) {
        if (!connections.isEmpty()) {
            disableAllInputProcessors(processGroup.getId());
            stopInputs(processGroup.getId());

            for (ConnectionDTO connection : NifiConnectionUtil.findConnectionsMatchingDestinationGroupId(connections, processGroup.getId())) {
                String type = connection.getSource().getType();
                if (NifiConstants.NIFI_PORT_TYPE.INPUT_PORT.name().equalsIgnoreCase(type)) {
                    stopInputPort(connection.getSource().getGroupId(), connection.getSource().getId());
                    deleteConnection(connection, false);
                }
            }
            for (ConnectionDTO connection : NifiConnectionUtil.findConnectionsMatchingSourceGroupId(connections, processGroup.getId())) {
                deleteConnection(connection, false);
            }
        }

        return deleteProcessGroup(processGroup);
    }

    public ConnectionEntity getConnection(String processGroupId, String connectionId) throws NifiComponentNotFoundException {
        try {
            return get("/controller/process-groups/" + processGroupId + "/connections/" + connectionId, null, ConnectionEntity.class);
        } catch (NotFoundException e) {
            throw new NifiComponentNotFoundException("Unable to find Connection for process Group: " + processGroupId + " and Connection Id " + connectionId, connectionId,
                                                     NifiConstants.NIFI_COMPONENT_TYPE.CONNECTION, e);
        }
    }

    public ListingRequestEntity getConnectionQueue(String processGroupId, String connectionId) {
        return postForm("/controller/process-groups/" + processGroupId + "/connections/" + connectionId + "/listing-requests", null, ListingRequestEntity.class);
    }

    public void deleteConnection(ConnectionDTO connection, boolean emptyQueue) {

        Map<String, Object> params = getUpdateParams();
        try {
            if (emptyQueue) {
                //empty connection Queue
                DropRequestEntity
                    dropRequestEntity =
                    delete("/controller/process-groups/" + connection.getParentGroupId() + "/connections/" + connection.getId() + "/contents", params,
                           DropRequestEntity.class);
                if (dropRequestEntity != null && dropRequestEntity.getDropRequest() != null) {
                    params = getUpdateParams();
                    delete("/controller/process-groups/" + connection.getParentGroupId() + "/connections/" + connection.getId() + "/drop-requests/"
                           + dropRequestEntity.getDropRequest().getId(), params, DropRequestEntity.class);
                }
            }
            //before deleting the connection we need to stop the source
            LOG.info("Before deleting the connection we need to stop Sources and destinations.");
            LOG.info("Stopping Source {} ({}) for connection {} ", connection.getSource().getId(), connection.getSource().getType(), connection.getId());
            String type = connection.getSource().getType();
            try {
                if (NifiConstants.NIFI_PORT_TYPE.OUTPUT_PORT.name().equalsIgnoreCase(type)) {
                    stopOutputPort(connection.getSource().getGroupId(), connection.getSource().getId());
                } else if (NifiConstants.NIFI_PORT_TYPE.INPUT_PORT.name().equalsIgnoreCase(type)) {
                    stopInputPort(connection.getSource().getGroupId(), connection.getSource().getId());
                } else if (NifiConstants.NIFI_PROCESSOR_TYPE.PROCESSOR.name().equalsIgnoreCase(type)) {
                    stopProcessor(connection.getSource().getGroupId(), connection.getSource().getId());
                }
            } catch (ClientErrorException e) {
                //swallow the  stop  exception.  it is ok at this point because Nifi might throw the exception if its still in the process of stopping...
                //TODO LOG IT
            }
            type = connection.getDestination().getType();
            LOG.info("Stopping Destination {} ({}) for connection {} ", connection.getDestination().getId(), connection.getDestination().getType(), connection.getId());
            try {
                if (NifiConstants.NIFI_PORT_TYPE.OUTPUT_PORT.name().equalsIgnoreCase(type)) {
                    stopOutputPort(connection.getDestination().getGroupId(), connection.getDestination().getId());
                } else if (NifiConstants.NIFI_PORT_TYPE.INPUT_PORT.name().equalsIgnoreCase(type)) {
                    stopInputPort(connection.getDestination().getGroupId(), connection.getDestination().getId());
                } else if (NifiConstants.NIFI_PROCESSOR_TYPE.PROCESSOR.name().equalsIgnoreCase(type)) {
                    stopProcessor(connection.getDestination().getGroupId(), connection.getDestination().getId());
                }
            } catch (ClientErrorException e) {
                //swallow the  stop  exception.  it is ok at this point because Nifi might throw the exception if its still in the process of stopping...
                //TODO LOG IT
            }
            LOG.info("Deleting the connection {} ", connection.getId());
            delete("/controller/process-groups/" + connection.getParentGroupId() + "/connections/" + connection.getId(), params,
                   ConnectionEntity.class);
            try {
                type = connection.getSource().getType();
                //now start the inputs again
                LOG.info("After Delete... Starting source again {} ({}) ", connection.getSource().getId(), connection.getSource().getType());
                if (NifiConstants.NIFI_PORT_TYPE.OUTPUT_PORT.name().equalsIgnoreCase(type)) {
                    startOutputPort(connection.getSource().getGroupId(), connection.getSource().getId());
                } else if (NifiConstants.NIFI_PORT_TYPE.INPUT_PORT.name().equalsIgnoreCase(type)) {
                    startInputPort(connection.getSource().getGroupId(), connection.getSource().getId());
                } else if (NifiConstants.NIFI_PROCESSOR_TYPE.PROCESSOR.name().equalsIgnoreCase(type)) {
                    startProcessor(connection.getSource().getGroupId(), connection.getSource().getId());
                }
            } catch (ClientErrorException e) {
                //swallow the  start  exception.  it is ok at this point because Nifi might throw the exception if its still in the process of starting...
                //TODO LOG IT
            }

            try {
                type = connection.getDestination().getType();
                //now start the inputs again
                LOG.info("After Delete... Starting dest again {} ({}) ", connection.getDestination().getId(), connection.getDestination().getType());
                if (NifiConstants.NIFI_PORT_TYPE.OUTPUT_PORT.name().equalsIgnoreCase(type)) {
                    startOutputPort(connection.getDestination().getGroupId(), connection.getDestination().getId());
                } else if (NifiConstants.NIFI_PORT_TYPE.INPUT_PORT.name().equalsIgnoreCase(type)) {
                    startInputPort(connection.getDestination().getGroupId(), connection.getDestination().getId());
                } else if (NifiConstants.NIFI_PROCESSOR_TYPE.PROCESSOR.name().equalsIgnoreCase(type)) {
                    startProcessor(connection.getDestination().getGroupId(), connection.getDestination().getId());
                }
            } catch (ClientErrorException e) {
                //swallow the  start  exception.  it is ok at this point because Nifi might throw the exception if its still in the process of starting...
                //TODO LOG IT
            }


        } catch (ClientErrorException e) {
            //swallow the  exception
            //TODO LOG IT
        }

    }

    public void deleteControllerService(String controllerServiceId) throws NifiClientRuntimeException {

        try { //http://localhost:8079/nifi-api/controller/controller-services/node/3c475f44-b038-4cb0-be51-65948de72764?version=1210&clientId=86af0022-9ba6-40b9-ad73-6d757b6f8d25
            Map<String, Object> params = getUpdateParams();
            delete("/controller/controller-services/" + getClusterType() + "/" + controllerServiceId, params, ControllerServiceEntity.class);
        } catch (NotFoundException e) {
            throw new NifiComponentNotFoundException(controllerServiceId, NifiConstants.NIFI_COMPONENT_TYPE.CONTROLLER_SERVICE, e);
        }
    }

    public void deleteControllerServices(Collection<ControllerServiceDTO> services) throws NifiClientRuntimeException {
        //http://localhost:8079/nifi-api/controller/controller-services/node/3c475f44-b038-4cb0-be51-65948de72764?version=1210&clientId=86af0022-9ba6-40b9-ad73-6d757b6f8d25
        Set<String> unableToDelete = new HashSet<>();
        for (ControllerServiceDTO dto : services) {
            try {
                deleteControllerService(dto.getId());
            } catch (Exception e) {
                if(!(e instanceof NifiComponentNotFoundException)) {
                    unableToDelete.add(dto.getId());
                }
            }
        }
        if (!unableToDelete.isEmpty()) {
            throw new NifiClientRuntimeException("Unable to Delete the following Services " + unableToDelete);
        }
    }

    //get a process and its connections
    //http://localhost:8079/nifi-api/controller/process-groups/e40bfbb2-4377-43e6-b6eb-369e8f39925d/connections

    public ConnectionsEntity getProcessGroupConnections(String processGroupId) throws NifiComponentNotFoundException {
        try {
            return get("/controller/process-groups/" + processGroupId + "/connections", null, ConnectionsEntity.class);
        } catch (NotFoundException e) {
            throw new NifiComponentNotFoundException(processGroupId, NifiConstants.NIFI_COMPONENT_TYPE.PROCESS_GROUP, e);
        }
    }

    public void removeConnectionsToProcessGroup(String parentProcessGroupId, final String processGroupId) {
        ConnectionsEntity connectionsEntity = getProcessGroupConnections(parentProcessGroupId);
        if (connectionsEntity != null && connectionsEntity.getConnections() != null) {
            List<ConnectionDTO> connections = Lists.newArrayList(Iterables.filter(connectionsEntity.getConnections(), new Predicate<ConnectionDTO>() {
                @Override
                public boolean apply(ConnectionDTO connectionDTO) {
                    return connectionDTO.getDestination().getGroupId().equals(processGroupId);
                }
            }));
            if (connections != null) {
                for (ConnectionDTO connectionDTO : connections) {
                    deleteConnection(connectionDTO, true);
                }
            }
        }

    }

    public ProcessGroupEntity getProcessGroup(String processGroupId, boolean recursive, boolean verbose) throws NifiComponentNotFoundException {
        try {
            Map<String, Object> params = new HashMap<String, Object>();
            params.put("recursive", recursive);
            params.put("verbose", verbose);
            return get("controller/process-groups/" + processGroupId, params, ProcessGroupEntity.class);
        } catch (NotFoundException e) {
            throw new NifiComponentNotFoundException(processGroupId, NifiConstants.NIFI_COMPONENT_TYPE.PROCESS_GROUP, e);
        }
    }

    /**
     * if the parentGroup is found but it cannot find the group by Name then it will return null
     */
    @Nullable
    public ProcessGroupDTO getProcessGroupByName(@Nonnull final String parentGroupId, @Nonnull final String groupName) throws NifiComponentNotFoundException {
        return getProcessGroupByName(parentGroupId, groupName, false, false);
    }

    /**
     * Gets the child process group with the specified name, optionally including all sub-components.
     *
     * @param parentGroupId the id of the parent process group
     * @param groupName the name of the process group to find
     * @param recursive {@code true} to include all encapsulated components, or {@code false} for just the immediate children
     * @param verbose {@code true} to include any encapsulated components, or {@code false} for just details about the process group
     * @return the child process group, or {@code null} if not found
     * @throws NifiComponentNotFoundException if the parent process group does not exist
     */
    @Nullable
    public ProcessGroupDTO getProcessGroupByName(@Nonnull final String parentGroupId, @Nonnull final String groupName, final boolean recursive, final boolean verbose) {
        final ProcessGroupsEntity children;
        try {
            children = get("/controller/process-groups/" + parentGroupId + "/process-group-references", null, ProcessGroupsEntity.class);
        } catch (NotFoundException e) {
            throw new NifiComponentNotFoundException(groupName, NifiConstants.NIFI_COMPONENT_TYPE.PROCESS_GROUP, e);
        }

        final ProcessGroupDTO group = (children != null) ? NifiProcessUtil.findFirstProcessGroupByName(children.getProcessGroups(), groupName) : null;
        if (group != null && verbose) {
            final ProcessGroupEntity verboseEntity = getProcessGroup(group.getId(), recursive, true);
            return (verboseEntity != null) ? verboseEntity.getProcessGroup() : null;
        } else {
            return group;
        }
    }

    public ProcessGroupEntity getRootProcessGroup() throws NifiComponentNotFoundException {
        try {
            Map<String, Object> params = new HashMap<String, Object>();
            params.put("recursive", true);
            params.put("verbose", true);
            return get("controller/process-groups/root", params, ProcessGroupEntity.class);
        } catch (NotFoundException e) {
            throw new NifiComponentNotFoundException("root", NifiConstants.NIFI_COMPONENT_TYPE.PROCESS_GROUP, e);
        }
    }


    /**
     * Disables all inputs for a given process group
     */
    public void disableAllInputProcessors(String processGroupId) throws NifiComponentNotFoundException {
        List<ProcessorDTO> processorDTOs = getInputProcessors(processGroupId);
        ProcessorDTO updateDto = new ProcessorDTO();
        if (processorDTOs != null) {
            for (ProcessorDTO dto : processorDTOs) {
                updateDto.setParentGroupId(dto.getParentGroupId());
                updateDto.setId(dto.getId());
                //fetch the processor and update it
                if (NifiProcessUtil.PROCESS_STATE.STOPPED.name().equals(dto.getState())) {
                    //if its stopped you can disable it.. otherwise stop it and then disable it
                    updateDto.setState(NifiProcessUtil.PROCESS_STATE.DISABLED.name());
                    updateProcessor(updateDto);
                }
                if (NifiProcessUtil.PROCESS_STATE.RUNNING.name().equals(dto.getState())) {
                    updateDto.setState(NifiProcessUtil.PROCESS_STATE.STOPPED.name());
                    updateProcessor(updateDto);
                    updateDto.setState(NifiProcessUtil.PROCESS_STATE.DISABLED.name());
                    updateProcessor(updateDto);
                }
            }
        }
        //also stop any input ports

        /*
        List<String> inputPortIds = getInputPortIds(processGroupId);
        if(inputPortIds != null && !inputPortIds.isEmpty())
        {
            for(String inputPortId : inputPortIds) {
             InputPortEntity inputPortEntity =   stopInputPort(processGroupId, inputPortId);
                int i =0;
            }
        }
        */
    }


    public void stopAllInputProcessors(String processGroupId) throws NifiComponentNotFoundException {
        List<ProcessorDTO> processorDTOs = getInputProcessors(processGroupId);
        ProcessorDTO updateDto = new ProcessorDTO();
        if (processorDTOs != null) {
            for (ProcessorDTO dto : processorDTOs) {
                updateDto.setParentGroupId(dto.getParentGroupId());
                updateDto.setId(dto.getId());
                //fetch the processor and update it
                if (NifiProcessUtil.PROCESS_STATE.RUNNING.name().equals(dto.getState())) {
                    //if its stopped you can disable it.. otherwise stop it and then disable it
                    updateDto.setState(NifiProcessUtil.PROCESS_STATE.STOPPED.name());
                    updateProcessor(updateDto);
                }
            }
        }
    }

    /**
     * Finds an input processor of the specified type within the specified process group and sets it to {@code RUNNING}. Other input processors are set to {@code DISABLED}.
     *
     * @param processGroupId the id of the NiFi process group to be searched
     * @param type the type (or Java class) of processor to set to {@code RUNNING}, or {@code null} to use the first processor
     * @return {@code true} if the processor was found, or {@code null} otherwise
     * @throws NifiComponentNotFoundException if the process group id is not valid
     */
    public boolean setInputAsRunningByProcessorMatchingType(@Nonnull final String processGroupId, @Nullable final String type) {
        // Get the processor list and the processor to be run
        final List<ProcessorDTO> processors = getInputProcessors(processGroupId);
        if (processors.isEmpty()) {
            return false;
        }

        final ProcessorDTO selected = StringUtils.isBlank(type) ? processors.get(0) : NifiProcessUtil.findFirstProcessorsByType(processors, type);
        if (selected == null) {
            return false;
        }

        // Set selected processor to RUNNING and others to DISABLED
        for (final ProcessorDTO processor : processors) {
            boolean update = false;

            // Verify state of processor
            if (!processor.equals(selected)) {
                if (!NifiProcessUtil.PROCESS_STATE.DISABLED.name().equals(processor.getState())) {
                    processor.setState(NifiProcessUtil.PROCESS_STATE.DISABLED.name());
                    update = true;
                }
            }
            else if (!NifiProcessUtil.PROCESS_STATE.RUNNING.name().equals(processor.getState())) {
                processor.setState(NifiProcessUtil.PROCESS_STATE.RUNNING.name());
                update = true;
            }

            // Update state of processor
            if (update) {
                // Stop processor before setting final state
                if (!NifiProcessUtil.PROCESS_STATE.STOPPED.name().equals(processor.getState())) {
                    stopProcessor(processor.getParentGroupId(), processor.getId());
                }

                // Set final state
                ProcessorEntity entity = new ProcessorEntity();
                ProcessorDTO updateDto = new ProcessorDTO();
                updateDto.setId(processor.getId());
                updateDto.setParentGroupId(processor.getParentGroupId());
                updateDto.setState(processor.getState());
                entity.setProcessor(updateDto);
                updateProcessor(entity);
            }
        }

        return true;
    }

    /**
     * return the Source Processors for a given group
     */
    public List<ProcessorDTO> getInputProcessors(String processGroupId) throws NifiComponentNotFoundException {
        //get the group
        ProcessGroupEntity processGroupEntity = getProcessGroup(processGroupId, false, true);
        //get the Source Processors
        List<String> sourceIds = getInputProcessorIds(processGroupId);
        return NifiProcessUtil.findProcessorsByIds(processGroupEntity.getProcessGroup().getContents().getProcessors(), sourceIds);
    }

    /**
     * @param template
     * @return
     */
    public List<ProcessorDTO> getInputProcessorsForTemplate(TemplateDTO template) {
        return NifiTemplateUtil.getInputProcessorsForTemplate(template);
    }


    /**
     * get a set of all ProcessorDTOs in a template and optionally remove the initial input ones
     */
    public Set<ProcessorDTO> getProcessorsForTemplate(String templateId, boolean excludeInputs) throws NifiComponentNotFoundException {
        TemplateDTO dto = getTemplateById(templateId);
        Set<ProcessorDTO> processors = NifiProcessUtil.getProcessors(dto);

        return processors;

    }

    /**
     * returns a list of Processors in a group that dont have any connection destinations (1st in the flow)
     */
    public List<String> getInputProcessorIds(String processGroupId) throws NifiComponentNotFoundException {
        List<String> processorIds = new ArrayList<>();
        ConnectionsEntity connections = null;
        try {
            connections = get("/controller/process-groups/" + processGroupId + "/connections", null,
                              ConnectionsEntity.class);
        } catch (NotFoundException e) {
            throw new NifiComponentNotFoundException(processGroupId, NifiConstants.NIFI_COMPONENT_TYPE.PROCESS_GROUP, e);
        }
        if (connections != null) {
            processorIds = NifiConnectionUtil.getInputProcessorIds(connections.getConnections());
        }
        return processorIds;
    }

    public List<String> getInputPortIds(String processGroupId) throws NifiComponentNotFoundException {
        List<String> ids = new ArrayList<>();
        ConnectionsEntity connections = null;
        try {
            connections = get("/controller/process-groups/" + processGroupId + "/connections", null,
                              ConnectionsEntity.class);
        } catch (NotFoundException e) {
            throw new NifiComponentNotFoundException(processGroupId, NifiConstants.NIFI_COMPONENT_TYPE.PROCESS_GROUP, e);
        }
        if (connections != null) {
            ids = NifiConnectionUtil.getInputPortIds(connections.getConnections());
        }
        return ids;
    }

    /**
     * returns a list of Processors in a group that dont have any connection destinations (1st in the flow)
     */
    public List<String> getEndingProcessorIds(String processGroupId) throws NifiComponentNotFoundException {
        List<String> processorIds = new ArrayList<>();
        ConnectionsEntity connections = null;
        try {
            connections = get("/controller/process-groups/" + processGroupId + "/connections", null,
                              ConnectionsEntity.class);
        } catch (NotFoundException e) {
            throw new NifiComponentNotFoundException(processGroupId, NifiConstants.NIFI_COMPONENT_TYPE.PROCESS_GROUP, e);
        }
        if (connections != null) {
            processorIds = NifiConnectionUtil.getEndingProcessorIds(connections.getConnections());
        }
        return processorIds;
    }

    /**
     * return the Source Processors for a given group
     */
    public List<ProcessorDTO> getEndingProcessors(String processGroupId) {
        //get the group
        ProcessGroupEntity processGroupEntity = getProcessGroup(processGroupId, false, true);
        //get the Source Processors
        List<String> sourceIds = getEndingProcessorIds(processGroupId);

        return NifiProcessUtil.findProcessorsByIds(processGroupEntity.getProcessGroup().getContents().getProcessors(), sourceIds);
    }


    /**
     * gets a Processor
     */
    public ProcessorEntity getProcessor(String processGroupId, String processorId) throws NifiComponentNotFoundException {
        try {
            return get("/controller/process-groups/" + processGroupId + "/processors/" + processorId, null, ProcessorEntity.class);
        } catch (NotFoundException e) {
            throw new NifiComponentNotFoundException(processorId, NifiConstants.NIFI_COMPONENT_TYPE.PROCESSOR, e);
        }
    }

    /**
     * Saves the Processor
     */
    public ProcessorEntity updateProcessor(ProcessorEntity processorEntity) {
        updateEntityForSave(processorEntity);
        return put(
            "/controller/process-groups/" + processorEntity.getProcessor().getParentGroupId() + "/processors/" + processorEntity
                .getProcessor().getId(), processorEntity, ProcessorEntity.class);
    }

    public ProcessorEntity updateProcessor(ProcessorDTO processorDTO) {
        ProcessorEntity processorEntity = new ProcessorEntity();
        processorEntity.setProcessor(processorDTO);
        updateEntityForSave(processorEntity);
        return put(
            "/controller/process-groups/" + processorEntity.getProcessor().getParentGroupId() + "/processors/" + processorEntity
                .getProcessor().getId(), processorEntity, ProcessorEntity.class);
    }


    public ProcessGroupEntity updateProcessGroup(ProcessGroupEntity processGroupEntity) {
        updateEntityForSave(processGroupEntity);
        return put("/controller/process-groups/" + processGroupEntity.getProcessGroup().getId(), processGroupEntity,
                   ProcessGroupEntity.class);
    }


    /**
     * Update the properties
     */
    public void updateProcessGroupProperties(List<NifiProperty> properties) {

        Map<String, Map<String, List<NifiProperty>>>
            processGroupProperties =
            NifiPropertyUtil.groupPropertiesByProcessGroupAndProcessor(properties);

        for (Map.Entry<String, Map<String, List<NifiProperty>>> processGroupEntry : processGroupProperties.entrySet()) {

            String processGroupId = processGroupEntry.getKey();
            for (Map.Entry<String, List<NifiProperty>> propertyEntry : processGroupEntry.getValue().entrySet()) {
                String processorId = propertyEntry.getKey();
                updateProcessorProperties(processGroupId, processorId, propertyEntry.getValue());
            }

        }
    }

    public void updateProcessorProperties(String processGroupId, String processorId, List<NifiProperty> properties) {
        Map<String, NifiProperty> propertyMap = NifiPropertyUtil.propertiesAsMap(properties);
        // fetch the processor
        ProcessorEntity processor = getProcessor(processGroupId, processorId);
        //iterate through and update the properties
        for (Map.Entry<String, NifiProperty> property : propertyMap.entrySet()) {
            processor.getProcessor().getConfig().getProperties().put(property.getKey(), property.getValue().getValue());
        }
        updateProcessor(processor);
    }

    public void updateProcessorProperty(String processGroupId, String processorId, NifiProperty property) {
        // fetch the processor
        ProcessorEntity processor = getProcessor(processGroupId, processorId);
        //iterate through and update the properties
        processor.getProcessor().getConfig().getProperties().put(property.getKey(), property.getValue());
        updateProcessor(processor);
    }

    public ControllerServicesEntity getControllerServices() {
        return getControllerServices(null);
    }

    public ControllerServicesEntity getControllerServices(String type) {

        if (StringUtils.isBlank(type)) {
            type = getClusterType();
        }
        return get("/controller/controller-services/" + type, null, ControllerServicesEntity.class);
    }

    public ControllerServiceEntity getControllerService(String type, String id) throws NifiComponentNotFoundException {
        try {
            if (StringUtils.isBlank(type)) {
                type = getClusterType();
            }
            return get("/controller/controller-services/" + type + "/" + id, null, ControllerServiceEntity.class);
        } catch (NotFoundException e) {
            throw new NifiComponentNotFoundException(id, NifiConstants.NIFI_COMPONENT_TYPE.CONTROLLER_SERVICE, e);
        }
    }

    /**
     * Returns Null if cant find it
     */
    public ControllerServiceDTO getControllerServiceByName(String type, final String serviceName) {
        ControllerServiceDTO controllerService = null;

        ControllerServicesEntity entity = getControllerServices(type);
        if (entity != null) {
            List<ControllerServiceDTO> services = Lists.newArrayList(Iterables.filter(entity.getControllerServices(), new Predicate<ControllerServiceDTO>() {
                @Override
                public boolean apply(ControllerServiceDTO controllerServiceDTO) {
                    return controllerServiceDTO.getName().equalsIgnoreCase(serviceName);
                }
            }));

            if (services != null) {

                for (ControllerServiceDTO controllerServiceDTO : services) {
                    if (controllerService == null) {
                        controllerService = controllerServiceDTO;
                    }
                    if (controllerServiceDTO.getState().equals(NifiProcessUtil.SERVICE_STATE.ENABLED.name())) {
                        controllerService = controllerServiceDTO;
                        break;
                    }
                }
            }
        }
        return controllerService;
    }

    //http://localhost:8079/nifi-api/controller/controller-services/node/edfe9a53-4fde-4437-a798-1305830c15ac
    public ControllerServiceEntity enableControllerService(String id) throws NifiClientRuntimeException {
        ControllerServiceEntity entity = getControllerService(null, id);
        ControllerServiceDTO dto = entity.getControllerService();
        if (!dto.getState().equals(NifiProcessUtil.SERVICE_STATE.ENABLED.name())) {
            dto.setState(NifiProcessUtil.SERVICE_STATE.ENABLED.name());

            entity.setControllerService(dto);
            updateEntityForSave(entity);
            return put("/controller/controller-services/" + getClusterType() + "/" + id, entity, ControllerServiceEntity.class);
        } else {
            return entity;
        }
    }

    /**
     * Enables the ControllerService and also replaces the properties if they match their keys
     */
    public ControllerServiceEntity enableControllerServiceAndSetProperties(String id, Map<String, String> properties) throws NifiClientRuntimeException {
        ControllerServiceEntity entity = getControllerService(null, id);
        ControllerServiceDTO dto = entity.getControllerService();
        //only need to do this if it is not enabled
        if (!dto.getState().equals(NifiProcessUtil.SERVICE_STATE.ENABLED.name())) {
            if (properties != null) {
                boolean changed = false;
                Map<String, String> resolvedProperties = NifiEnvironmentProperties.getEnvironmentControllerServiceProperties(properties, dto.getName());
                if (resolvedProperties != null && !resolvedProperties.isEmpty()) {
                    changed = ConfigurationPropertyReplacer.replaceControllerServiceProperties(dto, resolvedProperties);
                } else {
                    changed = ConfigurationPropertyReplacer.replaceControllerServiceProperties(dto, properties);
                }
                if (changed) {
                    //first save the property change
                    entity.setControllerService(dto);
                    updateEntityForSave(entity);
                    put("/controller/controller-services/" + getClusterType() + "/" + id, entity, ControllerServiceEntity.class);
                }
            }

            if (!dto.getState().equals(NifiProcessUtil.SERVICE_STATE.ENABLED.name())) {
                dto.setState(NifiProcessUtil.SERVICE_STATE.ENABLED.name());

                entity.setControllerService(dto);
                updateEntityForSave(entity);
                entity = put("/controller/controller-services/" + getClusterType() + "/" + id, entity, ControllerServiceEntity.class);
            }
        }
        //initially when trying to enable the service the state will be ENABLING
        //This will last until the service is up.
        //if it is in the ENABLING state it needs to wait and try again to get the status.

        dto = entity.getControllerService();
        if (dto.getState().equalsIgnoreCase(NifiProcessUtil.SERVICE_STATE.ENABLING.name())) {
            //attempt to retry x times before returning
            int retryCount = 0;
            int maxRetries = 5;
            while (retryCount <= maxRetries) {
                entity = getControllerService(null, id);
                if (!entity.getControllerService().getState().equals(NifiProcessUtil.SERVICE_STATE.ENABLED.name())) {
                    try {
                        Thread.sleep(3000);
                        retryCount++;
                    } catch (InterruptedException e2) {

                    }
                } else {
                    retryCount = maxRetries + 1;
                }
            }
        }
        return entity;
    }

    public ControllerServiceEntity disableControllerService(String id) throws NifiComponentNotFoundException {
        ControllerServiceEntity entity = new ControllerServiceEntity();
        ControllerServiceDTO dto = new ControllerServiceDTO();
        dto.setState(NifiProcessUtil.SERVICE_STATE.DISABLED.name());
        entity.setControllerService(dto);
        updateEntityForSave(entity);
        return put("/controller/controller-services/" + getClusterType() + "/" + id, entity, ControllerServiceEntity.class);
    }


    public ControllerServiceTypesEntity getControllerServiceTypes() {
        return get("/controller/controller-service-types", null, ControllerServiceTypesEntity.class);
    }


    public LineageEntity postLineageQuery(LineageEntity lineageEntity) {

        return post("/controller/provenance/lineage", lineageEntity, LineageEntity.class);
    }


    public ProvenanceEntity getProvenanceEntity(String provenanceId) {

        ProvenanceEntity entity = get("/controller/provenance/" + provenanceId, null, ProvenanceEntity.class);
        if (entity != null) {
            if (!entity.getProvenance().isFinished()) {
                return getProvenanceEntity(provenanceId);
            } else {
                //if it is finished you must delete the provenance entity
                try {
                    delete("/controller/provenance/" + provenanceId, null, ProvenanceEntity.class);
                } catch (ClientErrorException e) {
                    //swallow the exception.  Nothing we can do about it
                }
                return entity;
            }
        }
        return null;
    }

    public ProvenanceEventEntity getProvenanceEvent(String eventId) {
        ProvenanceEventEntity eventEntity = get("/controller/provenance/events/" + eventId, null, ProvenanceEventEntity.class);
        return eventEntity;
    }

    public AboutEntity getNifiVersion() {
        return get("/controller/about", null, AboutEntity.class);
    }


    public boolean isConnected(){
        AboutEntity aboutEntity = getNifiVersion();
        return aboutEntity != null;
    }

    public BulletinBoardEntity getBulletins(Map<String, Object> params) {

        BulletinBoardEntity entity = get("/controller/bulletin-board", params, BulletinBoardEntity.class);
        return entity;
    }

    public BulletinBoardEntity getProcessGroupBulletins(String processGroupId) {
        Map<String, Object> params = new HashMap<>();
        if (processGroupId != null) {
            params.put("groupId", processGroupId);
        }
        BulletinBoardEntity entity = get("/controller/bulletin-board", params, BulletinBoardEntity.class);
        return entity;
    }

    public BulletinBoardEntity getProcessorBulletins(String processorId) {
        Map<String, Object> params = new HashMap<>();
        if (processorId != null) {
            params.put("sourceId", processorId);
        }
        BulletinBoardEntity entity = get("/controller/bulletin-board", params, BulletinBoardEntity.class);
        return entity;
    }


    public InputPortEntity getInputPort(String groupId, String portId) throws NifiComponentNotFoundException {
        try {
            return get("/controller/process-groups/" + groupId + "/input-ports/" + portId, null, InputPortEntity.class);
        } catch (NotFoundException e) {
            throw new NifiComponentNotFoundException("Unable to find Input Port " + portId + " Under Process Group " + groupId, NifiConstants.NIFI_COMPONENT_TYPE.INPUT_PORT, e);
        }
    }

    public InputPortsEntity getInputPorts(String groupId) throws NifiComponentNotFoundException {
        try {
            return get("/controller/process-groups/" + groupId + "/input-ports/", null, InputPortsEntity.class);
        } catch (NotFoundException e) {
            throw new NifiComponentNotFoundException(groupId, NifiConstants.NIFI_COMPONENT_TYPE.PROCESS_GROUP, e);
        }
    }

    public OutputPortEntity getOutputPort(String groupId, String portId) {
        try {
            return get("/controller/process-groups/" + groupId + "/output-ports/" + portId, null, OutputPortEntity.class);
        } catch (NotFoundException e) {
            throw new NifiComponentNotFoundException("Unable to find Output Port " + portId + " Under Process Group " + groupId, NifiConstants.NIFI_COMPONENT_TYPE.OUTPUT_PORT, e);
        }
    }

    public OutputPortsEntity getOutputPorts(String groupId) throws NifiComponentNotFoundException {
        try {
            return get("/controller/process-groups/" + groupId + "/output-ports", null, OutputPortsEntity.class);
        } catch (NotFoundException e) {
            throw new NifiComponentNotFoundException(groupId, NifiConstants.NIFI_COMPONENT_TYPE.PROCESS_GROUP, e);
        }
    }

    public List<ConnectionDTO> findAllConnectionsMatchingDestinationId(String parentGroupId, String destinationId) throws NifiComponentNotFoundException {
        //get this parentGroup and find all connections under this parent that relate to this inputPortId
        //1. get this processgroup
        ProcessGroupEntity parentGroup = getProcessGroup(parentGroupId, false, false);
        //2 get the parent
        String parent = parentGroup.getProcessGroup().getParentGroupId();
        Set<ConnectionDTO> connectionDTOs = findConnectionsForParent(parent);
        List<ConnectionDTO> matchingConnections = NifiConnectionUtil.findConnectionsMatchingDestinationId(connectionDTOs,
                                                                                                          destinationId);

        return matchingConnections;

    }

    public void createReusableTemplateInputPort(String reusableTemplateCategoryGroupId, String reusableTemplateGroupId) throws NifiComponentNotFoundException {
        ProcessGroupEntity reusableTemplateGroup = getProcessGroup(reusableTemplateGroupId, false, false);
        ProcessGroupEntity reusableTemplateCategoryGroup = getProcessGroup(reusableTemplateCategoryGroupId, false, false);
        InputPortsEntity inputPortsEntity = getInputPorts(reusableTemplateGroupId);
        if (inputPortsEntity != null) {
            for (PortDTO inputPort : inputPortsEntity.getInputPorts()) {
                createReusableTemplateInputPort(reusableTemplateCategoryGroupId, reusableTemplateGroupId, inputPort.getName());
            }
        }
    }

    /**
     * Creates an INPUT Port and verifys/creates the connection from it to the child processgroup input port
     */
    public void createReusableTemplateInputPort(String reusableTemplateCategoryGroupId, String reusableTemplateGroupId,
                                                String inputPortName) {
        // ProcessGroupEntity reusableTemplateGroup = getProcessGroup(reusableTemplateGroupId, false, false);
        InputPortsEntity inputPortsEntity = getInputPorts(reusableTemplateCategoryGroupId);
        PortDTO inputPort = NifiConnectionUtil.findPortMatchingName(inputPortsEntity.getInputPorts(), inputPortName);
        if (inputPort == null) {

            //1 create the inputPort on the Category Group
            InputPortEntity inputPortEntity = new InputPortEntity();
            PortDTO portDTO = new PortDTO();
            portDTO.setParentGroupId(reusableTemplateCategoryGroupId);
            portDTO.setName(inputPortName);
            inputPortEntity.setInputPort(portDTO);
            updateEntityForSave(inputPortEntity);
            inputPortEntity =
                post("/controller/process-groups/" + reusableTemplateCategoryGroupId + "/input-ports", inputPortEntity,
                     InputPortEntity.class);
            inputPort = inputPortEntity.getInputPort();
        }
        //2 check and create the connection frmo the inputPort to the actual templateGroup
        PortDTO templateInputPort = null;
        InputPortsEntity templatePorts = getInputPorts(reusableTemplateGroupId);
        templateInputPort = NifiConnectionUtil.findPortMatchingName(templatePorts.getInputPorts(), inputPortName);

        ConnectionDTO
            inputToTemplateConnection =
            NifiConnectionUtil.findConnection(findConnectionsForParent(reusableTemplateCategoryGroupId), inputPort.getId(),
                                              templateInputPort.getId());
        if (inputToTemplateConnection == null) {
            //create the connection
            ConnectableDTO source = new ConnectableDTO();
            source.setGroupId(reusableTemplateCategoryGroupId);
            source.setId(inputPort.getId());
            source.setName(inputPortName);
            source.setType(NifiConstants.NIFI_PORT_TYPE.INPUT_PORT.name());
            ConnectableDTO dest = new ConnectableDTO();
            dest.setGroupId(reusableTemplateGroupId);
            //  dest.setName(reusableTemplateGroup.getProcessGroup().getName());
            dest.setId(templateInputPort.getId());
            dest.setType(NifiConstants.NIFI_PORT_TYPE.INPUT_PORT.name());
            createConnection(reusableTemplateCategoryGroupId, source, dest);
        }


    }

    /**
     * Connect a Feed to a reusable Template
     */
    public void connectFeedToGlobalTemplate(final String feedProcessGroupId, final String feedOutputName,
                                            final String categoryProcessGroupId, String reusableTemplateCategoryGroupId,
                                            String inputPortName) throws NifiComponentNotFoundException {
        ProcessGroupEntity categoryProcessGroup = getProcessGroup(categoryProcessGroupId, false, false);
        ProcessGroupEntity feedProcessGroup = getProcessGroup(feedProcessGroupId, false, false);
        ProcessGroupEntity categoryParent = getProcessGroup(categoryProcessGroup.getProcessGroup().getParentGroupId(), false, false);
        ProcessGroupEntity reusableTemplateCategoryGroup = getProcessGroup(reusableTemplateCategoryGroupId, false, false);

        //Go into the Feed and find the output port that is to be associated with the global template
        OutputPortsEntity outputPortsEntity = getOutputPorts(feedProcessGroupId);
        PortDTO feedOutputPort = null;
        if (outputPortsEntity != null) {
            feedOutputPort = NifiConnectionUtil.findPortMatchingName(outputPortsEntity.getOutputPorts(), feedOutputName);
        }
        if (feedOutputPort == null) {
            //ERROR  This feed needs to have an output port assigned on it to make the connection
        }

        InputPortsEntity inputPortsEntity = getInputPorts(reusableTemplateCategoryGroupId);
        PortDTO inputPort = NifiConnectionUtil.findPortMatchingName(inputPortsEntity.getInputPorts(), inputPortName);
        String inputPortId = inputPort.getId();

        final String categoryOutputPortName = categoryProcessGroup.getProcessGroup().getName() + " to " + inputPort.getName();

        //Find or create the output port that will join to the globabl template at the Category Level

        OutputPortsEntity categoryOutputPortsEntity = getOutputPorts(categoryProcessGroupId);
        PortDTO categoryOutputPort = null;
        if (categoryOutputPortsEntity != null) {
            categoryOutputPort =
                NifiConnectionUtil.findPortMatchingName(categoryOutputPortsEntity.getOutputPorts(), categoryOutputPortName);
        }
        if (categoryOutputPort == null) {
            //create it
            OutputPortEntity outputPortEntity = new OutputPortEntity();
            PortDTO portDTO = new PortDTO();
            portDTO.setParentGroupId(categoryProcessGroupId);
            portDTO.setName(categoryOutputPortName);
            outputPortEntity.setOutputPort(portDTO);
            updateEntityForSave(outputPortEntity);
            outputPortEntity =
                post("/controller/process-groups/" + categoryProcessGroupId + "/output-ports", outputPortEntity,
                     OutputPortEntity.class);
            categoryOutputPort = outputPortEntity.getOutputPort();
        }
        //Now create a connection from the Feed PRocessGroup to this outputPortEntity

        ConnectionDTO
            feedOutputToCategoryOutputConnection =
            NifiConnectionUtil.findConnection(findConnectionsForParent(categoryProcessGroupId), feedOutputPort.getId(),
                                              categoryOutputPort.getId());
        if (feedOutputToCategoryOutputConnection == null) {
            //CONNECT FEED OUTPUT PORT TO THE Category output port
            ConnectableDTO source = new ConnectableDTO();
            source.setGroupId(feedProcessGroupId);
            source.setId(feedOutputPort.getId());
            source.setName(feedProcessGroup.getProcessGroup().getName());
            source.setType(NifiConstants.NIFI_PORT_TYPE.OUTPUT_PORT.name());
            ConnectableDTO dest = new ConnectableDTO();
            dest.setGroupId(categoryProcessGroupId);
            dest.setName(categoryOutputPort.getName());
            dest.setId(categoryOutputPort.getId());
            dest.setType(NifiConstants.NIFI_PORT_TYPE.OUTPUT_PORT.name());
            createConnection(categoryProcessGroup.getProcessGroup().getId(), source, dest);
        }

        ConnectionDTO
            categoryToReusableTemplateConnection =
            NifiConnectionUtil
                .findConnection(findConnectionsForParent(categoryParent.getProcessGroup().getId()), categoryOutputPort.getId(),
                                inputPortId);

        //Now connect the category PRocessGroup to the global template
        if (categoryToReusableTemplateConnection == null) {
            ConnectableDTO categorySource = new ConnectableDTO();
            categorySource.setGroupId(categoryProcessGroupId);
            categorySource.setId(categoryOutputPort.getId());
            categorySource.setName(categoryOutputPortName);
            categorySource.setType(NifiConstants.NIFI_PORT_TYPE.OUTPUT_PORT.name());
            ConnectableDTO categoryToGlobalTemplate = new ConnectableDTO();
            categoryToGlobalTemplate.setGroupId(reusableTemplateCategoryGroupId);
            categoryToGlobalTemplate.setId(inputPortId);
            categoryToGlobalTemplate.setName(inputPortName);
            categoryToGlobalTemplate.setType(NifiConstants.NIFI_PORT_TYPE.INPUT_PORT.name());
            createConnection(categoryParent.getProcessGroup().getId(), categorySource, categoryToGlobalTemplate);
        }

    }


    public Set<ConnectionDTO> findConnectionsForParent(String parentProcessGroupId) throws NifiComponentNotFoundException {
        Set<ConnectionDTO> connections = new HashSet<>();
        //get all connections under parent group
        ConnectionsEntity connectionsEntity = getProcessGroupConnections(parentProcessGroupId);
        if (connectionsEntity != null) {
            connections = connectionsEntity.getConnections();
        }
        return connections;
    }


    public ConnectionDTO findConnection(String parentProcessGroupId, final String sourceProcessGroupId,
                                        final String destProcessGroupId) throws NifiComponentNotFoundException {
        return NifiConnectionUtil.findConnection(findConnectionsForParent(parentProcessGroupId), sourceProcessGroupId,
                                                 parentProcessGroupId);
    }


    public ConnectionEntity createConnection(String processGroupId, ConnectableDTO source, ConnectableDTO dest) {
        ConnectionDTO connectionDTO = new ConnectionDTO();
        connectionDTO.setSource(source);
        connectionDTO.setDestination(dest);
        connectionDTO.setName(source.getName() + " - " + dest.getName());
        ConnectionEntity connectionEntity = new ConnectionEntity();
        connectionEntity.setConnection(connectionDTO);
        updateEntityForSave(connectionEntity);
        return post("/controller/process-groups/" + processGroupId + "/connections", connectionEntity, ConnectionEntity.class);
    }


    public NifiVisitableProcessGroup getFlowOrder(String processGroupId) throws NifiComponentNotFoundException {
        NifiVisitableProcessGroup group = null;
        ProcessGroupEntity processGroupEntity = getProcessGroup(processGroupId, true, true);
        if (processGroupEntity != null) {
            group = new NifiVisitableProcessGroup(processGroupEntity.getProcessGroup());
            NifiConnectionOrderVisitor orderVisitor = new NifiConnectionOrderVisitor(this, group);
            try {
                //find the parent just to get hte names andids
                ProcessGroupEntity parent = getProcessGroup(processGroupEntity.getProcessGroup().getParentGroupId(), false, false);
                group.setParentProcessGroup(parent.getProcessGroup());
            } catch (NifiComponentNotFoundException e) {
                //cant find the parent
            }

            group.accept(orderVisitor);
           //  orderVisitor.printOrder();
            //orderVisitor.printOrder();
        }
        return group;
    }

    public NifiFlowProcessGroup getFlowForProcessGroup(String processGroupId) {
        NifiFlowProcessGroup group = getFeedFlow(processGroupId);
        log.info("********************** getFlowForProcessGroup  ({})", group);
        NifiFlowDeserializer.constructGraph(group);
        return group;
    }



    public List<NifiFlowProcessGroup> getAllFlows() {
        log.info("********************** STARTING getAllFlows  ");
        System.out.println("********************** STARTING getAllFlows  ");
        List<NifiFlowProcessGroup> groups = getFeedFlows();
        if (groups != null) {
            System.out.println("********************** finished getAllFlows .. construct graph   " + groups.size());
            log.info("********************** getAllFlows  ({})", groups.size());
            groups.stream().forEach(group -> NifiFlowDeserializer.constructGraph(group));
        } else {
            log.info("********************** getAllFlows  (NULL!!!!)");
        }
        return groups;
    }

    public SearchResultsEntity search(String query) {
        Map<String,Object> map = new HashMap<>();
        map.put("q", query);
        return get("/controller/search-results", map, SearchResultsEntity.class);
    }

    public ProcessorDTO findProcessorById(String processorId){
        SearchResultsEntity results = search(processorId);
        //log this
        if(results != null && results.getSearchResultsDTO() != null && results.getSearchResultsDTO().getProcessorResults() != null && !results.getSearchResultsDTO().getProcessorResults().isEmpty()){
            log.info("Attempt to find processor by id {}. Processors Found: {} ",processorId,results.getSearchResultsDTO().getProcessorResults().size());
            ComponentSearchResultDTO processorResult =  results.getSearchResultsDTO().getProcessorResults().get(0);
            String id = processorResult.getId();
            String groupId = processorResult.getGroupId();
            ProcessorEntity processorEntity = getProcessor(groupId,id);

            if(processorEntity != null){
                return processorEntity.getProcessor();
            }
        }
        else {
            log.info("Unable to find Processor in Nifi for id: {}",processorId);
        }
        return null;
    }


    public NifiFlowProcessGroup getFeedFlow(String processGroupId) throws NifiComponentNotFoundException {
        NifiFlowProcessGroup group = null;
        NifiVisitableProcessGroup visitableGroup = getFlowOrder(processGroupId);
        NifiFlowProcessGroup flow = new NifiFlowBuilder().build(visitableGroup);
        String categoryName = flow.getParentGroupName();
        String feedName = flow.getName();
        feedName = FeedNameUtil.fullName(categoryName, feedName);
        //if it is a versioned feed then strip the version to get the correct feed name
        feedName = TemplateCreationHelper.parseVersionedProcessGroupName(feedName);
        flow.setFeedName(feedName);
        return flow;
    }

    public Set<ProcessorDTO> getProcessorsForFlow(String processGroupId) throws NifiComponentNotFoundException {
        NifiVisitableProcessGroup group = getFlowOrder(processGroupId);
        Set<ProcessorDTO> processors = new HashSet<>();
        for (NifiVisitableProcessor p : group.getStartingProcessors()) {
            processors.addAll(p.getProcessors());
        }
        return processors;
    }


    public NifiFlowProcessGroup getFeedFlowForCategoryAndFeed(String categoryAndFeedName) {
        NifiFlowProcessGroup flow = null;
        String category = FeedNameUtil.category(categoryAndFeedName);
        String feed = FeedNameUtil.feed(categoryAndFeedName);
        //1 find the ProcessGroup under "root" matching the name category
        ProcessGroupEntity processGroupEntity = getRootProcessGroup();
        ProcessGroupDTO root = processGroupEntity.getProcessGroup();
        ProcessGroupDTO categoryGroup = root.getContents().getProcessGroups().stream().filter(group -> category.equalsIgnoreCase(group.getName())).findAny().orElse(null);
        if (categoryGroup != null) {
            ProcessGroupDTO feedGroup = categoryGroup.getContents().getProcessGroups().stream().filter(group -> feed.equalsIgnoreCase(group.getName())).findAny().orElse(null);
            if (feedGroup != null) {
                flow = getFeedFlow(feedGroup.getId());
            }
        }
        return flow;
    }


    //walk entire graph
    public List<NifiFlowProcessGroup> getFeedFlows() {
        log.info("get Graph of Nifi Flows");
        List<NifiFlowProcessGroup> feedFlows = new ArrayList<>();
        ProcessGroupEntity processGroupEntity = getRootProcessGroup();
        ProcessGroupDTO root = processGroupEntity.getProcessGroup();
        //first level is the category
        for (ProcessGroupDTO category : root.getContents().getProcessGroups()) {
            for (ProcessGroupDTO feedProcessGroup : category.getContents().getProcessGroups()) {
                //second level is the feed
                String feedName = FeedNameUtil.fullName(category.getName(), feedProcessGroup.getName());
                //if it is a versioned feed then strip the version to get the correct feed name
                feedName = TemplateCreationHelper.parseVersionedProcessGroupName(feedName);
                NifiFlowProcessGroup feedFlow = getFeedFlow(feedProcessGroup.getId());
                feedFlow.setFeedName(feedName);
                feedFlows.add(feedFlow);
            }
        }
        log.info("finished Graph of Nifi Flows.  Returning {} flows", feedFlows.size());
        return feedFlows;
    }



    /**
     * Wallk the flow for a given Root Process Group and return all those Processors who are marked with a Failure Relationship
     */
    public Set<ProcessorDTO> getFailureProcessors(String processGroupId) throws NifiComponentNotFoundException {
        NifiVisitableProcessGroup g = getFlowOrder(processGroupId);
        Set<ProcessorDTO> failureProcessors = new HashSet<>();
        for (NifiVisitableProcessor p : g.getStartingProcessors()) {

            failureProcessors.addAll(p.getFailureProcessors());
        }

        return failureProcessors;
    }


    public ProvenanceEventEntity replayProvenanceEvent(Long eventId) {
        Form form = new Form();
        form.param("eventId", eventId.toString());
        try {
            Entity controller = getControllerRevision();
            if (controller != null && controller.getRevision() != null) {
                form.param("clientId", controller.getRevision().getClientId());
            }
        } catch (ClientErrorException e) {

        }

        return postForm("/controller/provenance/replays", form, ProvenanceEventEntity.class);
    }


}


