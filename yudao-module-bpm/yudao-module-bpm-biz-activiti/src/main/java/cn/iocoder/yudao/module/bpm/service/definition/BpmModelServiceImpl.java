package cn.iocoder.yudao.module.bpm.service.definition;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.util.validation.ValidationUtils;
import cn.iocoder.yudao.module.bpm.controller.admin.definition.vo.model.*;
import cn.iocoder.yudao.module.bpm.convert.definition.BpmModelConvert;
import cn.iocoder.yudao.module.bpm.enums.definition.BpmModelFormTypeEnum;
import cn.iocoder.yudao.module.bpm.service.definition.dto.BpmModelMetaInfoRespDTO;
import cn.iocoder.yudao.framework.activiti.core.util.ActivitiUtils;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.collection.CollectionUtils;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.common.util.object.PageUtils;
import cn.iocoder.yudao.module.bpm.controller.admin.definition.vo.rule.BpmTaskAssignRuleRespVO;
import cn.iocoder.yudao.module.bpm.dal.dataobject.definition.BpmFormDO;
import cn.iocoder.yudao.module.bpm.service.definition.dto.BpmProcessDefinitionCreateReqDTO;
import lombok.extern.slf4j.Slf4j;
import org.activiti.bpmn.model.BpmnModel;
import org.activiti.engine.RepositoryService;
import org.activiti.engine.impl.persistence.entity.SuspensionState;
import org.activiti.engine.repository.Deployment;
import org.activiti.engine.repository.Model;
import org.activiti.engine.repository.ModelQuery;
import org.activiti.engine.repository.ProcessDefinition;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.ObjectUtils;
import org.springframework.validation.annotation.Validated;

import javax.annotation.Resource;
import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertMap;
import static cn.iocoder.yudao.module.bpm.enums.ErrorCodeConstants.*;

/**
 * ??????????????????
 * ???????????? Activiti {@link Model} ?????????
 *
 * @author yunlongn
 * @author ????????????
 */
@Service
@Validated
@Slf4j
public class BpmModelServiceImpl  implements BpmModelService {

    @Resource
    private RepositoryService repositoryService;
    @Resource
    private BpmProcessDefinitionService processDefinitionService;
    @Resource
    private BpmFormService bpmFormService;
    @Resource
    private BpmTaskAssignRuleService taskAssignRuleService;

    @Override
    public PageResult<BpmModelPageItemRespVO> getModelPage(BpmModelPageReqVO pageVO) {
        ModelQuery modelQuery = repositoryService.createModelQuery();
        if (StrUtil.isNotBlank(pageVO.getKey())) {
            modelQuery.modelKey(pageVO.getKey());
        }
        if (StrUtil.isNotBlank(pageVO.getName())) {
            modelQuery.modelNameLike("%" + pageVO.getName() + "%"); // ????????????
        }
        if (StrUtil.isNotBlank(pageVO.getCategory())) {
            modelQuery.modelCategory(pageVO.getCategory());
        }
        // ????????????
        List<Model> models = modelQuery.orderByCreateTime().desc()
                .listPage(PageUtils.getStart(pageVO), pageVO.getPageSize());

        // ?????? Form Map
        Set<Long> formIds = CollectionUtils.convertSet(models, model -> {
            BpmModelMetaInfoRespDTO metaInfo = JsonUtils.parseObject(model.getMetaInfo(), BpmModelMetaInfoRespDTO.class);
            return metaInfo != null ? metaInfo.getFormId() : null;
        });
        Map<Long, BpmFormDO> formMap = bpmFormService.getFormMap(formIds);

        // ?????? Deployment Map
        Set<String> deploymentIds = new HashSet<>();
        models.forEach(model -> CollectionUtils.addIfNotNull(deploymentIds, model.getDeploymentId()));
        Map<String, Deployment> deploymentMap = processDefinitionService.getDeploymentMap(deploymentIds);
        // ?????? ProcessDefinition Map
        List<ProcessDefinition> processDefinitions = processDefinitionService.getProcessDefinitionListByDeploymentIds(deploymentIds);
        Map<String, ProcessDefinition> processDefinitionMap = convertMap(processDefinitions, ProcessDefinition::getDeploymentId);

        // ????????????
        long modelCount = modelQuery.count();
        return new PageResult<>(BpmModelConvert.INSTANCE.convertList(models, formMap, deploymentMap, processDefinitionMap), modelCount);
    }

    @Override
    public BpmModelRespVO getModel(String id) {
        Model model = repositoryService.getModel(id);
        if (model == null) {
            return null;
        }
        BpmModelRespVO modelRespVO = BpmModelConvert.INSTANCE.convert(model);
        // ?????? bpmn XML
        byte[] bpmnBytes = repositoryService.getModelEditorSource(id);
        modelRespVO.setBpmnXml(StrUtil.utf8Str(bpmnBytes));
        return modelRespVO;
    }

    @Override
    @Transactional(rollbackFor = Exception.class) // ?????????????????? activiti ???????????????????????????
    public String createModel(BpmModelCreateReqVO createReqVO, String bpmnXml) {
        checkKeyNCName(createReqVO.getKey());
        // ??????????????????????????????
        Model keyModel = this.getModelByKey(createReqVO.getKey());
        if (keyModel != null) {
            throw exception(MODEL_KEY_EXISTS, createReqVO.getKey());
        }

        // ??????????????????
        Model model = repositoryService.newModel();
        BpmModelConvert.INSTANCE.copy(model, createReqVO);
        // ??????????????????
        repositoryService.saveModel(model);
        // ?????? BPMN XML
        saveModelBpmnXml(model, bpmnXml);
        return model.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class) // ?????????????????? activiti ???????????????????????????
    public void updateModel(BpmModelUpdateReqVO updateReqVO) {
        // ????????????????????????
        Model model = repositoryService.getModel(updateReqVO.getId());
        if (model == null) {
            throw exception(MODEL_NOT_EXISTS);
        }

        // ??????????????????
        BpmModelConvert.INSTANCE.copy(model, updateReqVO);
        // ????????????
        repositoryService.saveModel(model);
        // ?????? BPMN XML
        saveModelBpmnXml(model, updateReqVO.getBpmnXml());
    }

    private void saveModelBpmnXml(Model model, String bpmnXml) {
        if (StrUtil.isEmpty(bpmnXml)) {
            return;
        }
        repositoryService.addModelEditorSource(model.getId(), StrUtil.utf8Bytes(bpmnXml));
    }

    @Override
    @Transactional(rollbackFor = Exception.class) // ?????????????????? activiti ???????????????????????????
    public void deployModel(String id) {
        // ????????????????????????
        Model model = repositoryService.getModel(id);
        if (ObjectUtils.isEmpty(model)) {
            throw exception(MODEL_NOT_EXISTS);
        }
        // ???????????????
        byte[] bpmnBytes = repositoryService.getModelEditorSource(model.getId());
        if (bpmnBytes == null) {
            throw exception(MODEL_NOT_EXISTS);
        }
        // TODO ?????????????????????????????????????????????????????????????????????????????????????????????????????????
        // ??????????????????
        BpmFormDO form = checkFormConfig(model.getMetaInfo());
        // ?????????????????????????????????
        taskAssignRuleService.checkTaskAssignRuleAllConfig(id);

        // ?????????????????????????????????????????????????????????????????????
        BpmProcessDefinitionCreateReqDTO definitionCreateReqDTO = BpmModelConvert.INSTANCE.convert2(model, form).setBpmnBytes(bpmnBytes);
        if (processDefinitionService.isProcessDefinitionEquals(definitionCreateReqDTO)) { // ???????????????????????????
            ProcessDefinition oldProcessInstance = processDefinitionService.getProcessDefinitionByDeploymentId(model.getDeploymentId());
            if (oldProcessInstance != null && taskAssignRuleService.isTaskAssignRulesEquals(model.getId(), oldProcessInstance.getId())) {
                throw exception(MODEL_DEPLOY_FAIL_TASK_INFO_EQUALS);
            }
        }
        // ??????????????????
        String definitionId = processDefinitionService.createProcessDefinition(definitionCreateReqDTO);

        // ???????????????????????????????????????????????????????????????????????????????????????????????????????????????
        updateProcessDefinitionSuspended(model.getDeploymentId());

        // ?????? model ??? deploymentId???????????????
        ProcessDefinition definition = processDefinitionService.getProcessDefinition(definitionId);
        model.setDeploymentId(definition.getDeploymentId());
        repositoryService.saveModel(model);

        // ????????????????????????
        taskAssignRuleService.copyTaskAssignRules(id, definition.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteModel(String id) {
        // ????????????????????????
        Model model = repositoryService.getModel(id);
        if (model == null) {
            throw exception(MODEL_NOT_EXISTS);
        }
        // ????????????
        repositoryService.deleteModel(id);
        // ??????????????????
        updateProcessDefinitionSuspended(model.getDeploymentId());
    }

    private void updateProcessDefinitionSuspended(String deploymentId) {
        if (StrUtil.isEmpty(deploymentId)) {
            return;
        }
        ProcessDefinition oldDefinition = processDefinitionService.getProcessDefinitionByDeploymentId(deploymentId);
        if (oldDefinition == null) {
            return;
        }
        if(oldDefinition.isSuspended()) {
            return;
        }
        processDefinitionService.updateProcessDefinitionState(oldDefinition.getId(), SuspensionState.SUSPENDED.getStateCode());
    }

    @Override
    public void updateModelState(String id, Integer state) {
        // ????????????????????????
        Model model = repositoryService.getModel(id);
        if (model == null) {
            throw exception(MODEL_NOT_EXISTS);
        }
        // ????????????????????????
        ProcessDefinition definition = processDefinitionService.getProcessDefinitionByDeploymentId(model.getDeploymentId());
        if (definition == null) {
            throw exception(PROCESS_DEFINITION_NOT_EXISTS);
        }

        // ????????????
        processDefinitionService.updateProcessDefinitionState(definition.getId(), state);
    }

    @Override
    public BpmnModel getBpmnModel(String id) {
        byte[] bpmnBytes = repositoryService.getModelEditorSource(id);
        if (ArrayUtil.isEmpty(bpmnBytes)) {
            return null;
        }
        return ActivitiUtils.buildBpmnModel(bpmnBytes);
    }

    private Model getModelByKey(String key) {
        return repositoryService.createModelQuery().modelKey(key).singleResult();
    }

    private void checkKeyNCName(String key) {
        if (!ValidationUtils.isXmlNCName(key)) {
            throw exception(MODEL_KEY_VALID);
        }
    }

    /**
     * ???????????????????????????
     *
     * @param metaInfoStr ???????????? metaInfo ??????
     * @return ????????????
     */
    private BpmFormDO checkFormConfig(String  metaInfoStr) {
        BpmModelMetaInfoRespDTO metaInfo = JsonUtils.parseObject(metaInfoStr, BpmModelMetaInfoRespDTO.class);
        if (metaInfo == null || metaInfo.getFormType() == null) {
            throw exception(MODEL_DEPLOY_FAIL_FORM_NOT_CONFIG);
        }
        // ??????????????????
        if (Objects.equals(metaInfo.getFormType(), BpmModelFormTypeEnum.NORMAL.getType())) {
            BpmFormDO form = bpmFormService.getForm(metaInfo.getFormId());
            if (form == null) {
                throw exception(FORM_NOT_EXISTS);
            }
            return form;
        }
        return null;
    }

}
