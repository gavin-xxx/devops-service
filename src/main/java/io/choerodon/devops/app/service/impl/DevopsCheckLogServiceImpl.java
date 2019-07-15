package io.choerodon.devops.app.service.impl;

import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.github.pagehelper.PageInfo;
import com.google.gson.Gson;
import com.zaxxer.hikari.util.UtilityElf;
import io.choerodon.asgard.saga.annotation.Saga;
import io.choerodon.asgard.saga.dto.StartInstanceDTO;
import io.choerodon.asgard.saga.feign.SagaClient;
import io.choerodon.base.domain.PageRequest;
import io.choerodon.core.exception.CommonException;
import io.choerodon.core.iam.ResourceLevel;
import io.choerodon.devops.api.vo.ProjectReqVO;
import io.choerodon.devops.api.vo.ProjectVO;
import io.choerodon.devops.api.vo.iam.UserWithRoleDTO;
import io.choerodon.devops.api.vo.iam.entity.*;
import io.choerodon.devops.api.vo.iam.entity.gitlab.GitlabJobE;
import io.choerodon.devops.api.vo.iam.entity.gitlab.GitlabMemberE;
import io.choerodon.devops.api.vo.iam.entity.gitlab.GitlabPipelineE;
import io.choerodon.devops.api.vo.iam.entity.gitlab.GitlabUserE;
import io.choerodon.devops.api.vo.iam.entity.iam.UserE;
import io.choerodon.devops.app.eventhandler.payload.GitlabProjectPayload;
import io.choerodon.devops.app.eventhandler.payload.IamAppPayLoad;
import io.choerodon.devops.app.service.*;
import io.choerodon.devops.domain.application.repository.*;
import io.choerodon.devops.domain.application.valueobject.*;
import io.choerodon.devops.infra.dataobject.DevopsProjectDTO;
import io.choerodon.devops.infra.dataobject.gitlab.CommitDTO;
import io.choerodon.devops.infra.dto.*;
import io.choerodon.devops.infra.dto.gitlab.BranchDTO;
import io.choerodon.devops.infra.dto.gitlab.CommitStatuseDTO;
import io.choerodon.devops.infra.dto.gitlab.GroupDTO;
import io.choerodon.devops.infra.dto.gitlab.ProjectHookDTO;
import io.choerodon.devops.infra.enums.ResourceType;
import io.choerodon.devops.infra.feign.GitlabServiceClient;
import io.choerodon.devops.infra.feign.SonarClient;
import io.choerodon.devops.infra.handler.ClusterConnectionHandler;
import io.choerodon.devops.infra.handler.RetrofitHandler;
import io.choerodon.devops.infra.mapper.*;
import io.choerodon.devops.infra.util.*;
import io.kubernetes.client.models.V1Pod;
import org.apache.commons.lang.StringUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.Tag;


@Service
public class DevopsCheckLogServiceImpl implements DevopsCheckLogService {

    private static final String SONAR = "sonar";
    private static final String SONARQUBE = "sonarqube";
    private static final String TWELVE_VERSION = "0.12.0";
    private static final String APP = "app: ";
    private static final Integer ADMIN = 1;
    private static final String ENV = "ENV";
    private static final String SERVICE_LABEL = "choerodon.io/network";
    private static final String PROJECT_OWNER = "role/project/default/project-owner";
    private static final String SERVICE = "service";
    private static final String SUCCESS = "success";
    private static final String FAILED = "failed: ";
    private static final String SERIAL_STRING = " serializable to yaml";
    private static final String APPLICATION = "application";
    private static final String FILE_SEPARATOR = "file.separator";
    private static final String PERMISSION = "permission";
    private static final String YAML_SUFFIX = ".yaml";
    private static final Logger LOGGER = LoggerFactory.getLogger(DevopsCheckLogServiceImpl.class);
    private static final ExecutorService executorService = new ThreadPoolExecutor(0, 1,
            0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(), new UtilityElf.DefaultThreadFactory("devops-upgrade", false));
    private static final String SERVICE_PATTERN = "[a-zA-Z0-9_\\.][a-zA-Z0-9_\\-\\.]*[a-zA-Z0-9_\\-]|[a-zA-Z0-9_]";
    private static io.kubernetes.client.JSON json = new io.kubernetes.client.JSON();
    @Value("${services.sonarqube.url:}")
    private String sonarqubeUrl;
    @Value("${services.sonarqube.username:}")
    private String userName;
    @Value("${services.sonarqube.password:}")
    private String password;
    private Gson gson = new Gson();
    @Value("${services.gateway.url}")
    private String gatewayUrl;
    @Value("${services.helm.url}")
    private String helmUrl;

    @Autowired
    private ApplicationMapper applicationMapper;
    @Autowired
    private DevopsEnvironmentMapper devopsEnvironmentMapper;
    @Autowired
    private GitlabRepository gitlabRepository;
    @Autowired
    private OrgRepository orgRepository;
    @Autowired
    private UserAttrRepository userAttrRepository;
    @Autowired
    private DevopsCheckLogRepository devopsCheckLogRepository;
    @Autowired
    private GitlabServiceClient gitlabServiceClient;
    @Autowired
    private DevopsGitRepository devopsGitRepository;
    @Autowired
    private IamRepository iamRepository;
    @Autowired
    private DevopsProjectRepository devopsProjectRepository;
    @Autowired
    private DevopsEnvironmentRepository devopsEnvironmentRepository;
    @Autowired
    private DevopsEnvironmentService devopsEnvironmentService;
    @Autowired
    private ApplicationInstanceRepository applicationInstanceRepository;
    @Autowired
    private ApplicationVersionRepository applicationVersionRepository;
    @Autowired
    private ApplicationRepository applicationRepository;
    @Autowired
    private ApplicationInstanceService applicationInstanceService;
    @Autowired
    private DevopsServiceRepository devopsServiceRepository;
    @Autowired
    private DevopsIngressRepository devopsIngressRepository;
    @Autowired
    private DevopsIngressService devopsIngressService;
    @Autowired
    private SagaClient sagaClient;
    @Autowired
    private DevopsEnvResourceDetailRepository devopsEnvResourceDetailRepository;
    @Autowired
    private DevopsEnvResourceRepository devopsEnvResourceRepository;
    @Autowired
    private DevopsServiceInstanceRepository devopsServiceInstanceRepository;
    @Autowired
    private GitlabProjectRepository gitlabProjectRepository;
    @Autowired
    private DevopsGitlabCommitRepository devopsGitlabCommitRepository;
    @Autowired
    private DevopsGitlabPipelineRepository devopsGitlabPipelineRepository;
    @Autowired
    private DevopsGitlabPipelineMapper devopsGitlabPipelineMapper;
    @Autowired
    private DevopsGitlabCommitMapper devopsGitlabCommitMapper;
    @Autowired
    private DevopsProjectMapper devopsProjectMapper;
    @Autowired
    private GitlabGroupMemberRepository gitlabGroupMemberRepository;
    @Autowired
    private GitlabUserRepository gitlabUserRepository;
    @Autowired
    private DevopsEnvPodMapper devopsEnvPodMapper;
    @Autowired
    private DevopsClusterRepository clusterRepository;
    @Autowired
    private GitUtil gitUtil;
    @Autowired
    private ClusterConnectionHandler clusterConnectionHandler;
    @Autowired
    private ApplicationVersionMapper applicationVersionMapper;
    @Autowired
    private DevopsProjectConfigRepository devopsProjectConfigRepository;
    @Autowired
    private ApplicationService applicationService;
    @Autowired
    private DevopsEnvCommandRepository devopsEnvCommandRepository;
    @Autowired
    private DevopsEnvCommandValueRepository devopsEnvCommandValueRepository;
    @Autowired
    private DevopsEnvApplicationRepostitory devopsEnvApplicationRepostitory;
    @Autowired
    private AppShareRepository appShareRepository;
    @Autowired
    private DevopsCheckLogMapper devopsCheckLogMapper;

    @Override
    public void checkLog(String version) {
        LOGGER.info("start upgrade task");
        executorService.submit(new UpgradeTask(version));
    }


    private void createGitFile(String repoPath, Git git, String relativePath, String content) {
        GitUtil newGitUtil = new GitUtil();
        try {
            newGitUtil.createFileInRepo(repoPath, git, relativePath, content, null);
        } catch (IOException e) {
            LOGGER.info("error.file.open: " + relativePath, e);
        } catch (GitAPIException e) {
            LOGGER.info("error.git.commit: " + relativePath, e);
        }

    }

    private String getObjectYaml(Object object) {
        Tag tag = new Tag(object.getClass().toString());
        SkipNullRepresenterUtil skipNullRepresenter = new SkipNullRepresenterUtil();
        skipNullRepresenter.addClassTag(object.getClass(), tag);
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setAllowReadOnlyProperties(true);
        Yaml yaml = new Yaml(skipNullRepresenter, options);
        return yaml.dump(object).replace("!<" + tag.getValue() + ">", "---");
    }


    private void updateWebHook(List<CheckLog> logs) {
        List<ApplicationDTO> applications = applicationMapper.selectAll();
        applications.stream()
                .filter(applicationDO ->
                        applicationDO.getHookId() != null)
                .forEach(applicationDO -> {
                    CheckLog checkLog = new CheckLog();
                    checkLog.setContent(APP + applicationDO.getName() + "update gitlab webhook");
                    try {
                        gitlabRepository.updateWebHook(applicationDO.getGitlabProjectId(), TypeUtil.objToInteger(applicationDO.getHookId()), ADMIN);
                        checkLog.setResult(SUCCESS);
                    } catch (Exception e) {
                        checkLog.setResult(FAILED + e.getMessage());
                    }
                    logs.add(checkLog);
                });
    }

    private void syncCommit(List<CheckLog> logs) {
        List<ApplicationDTO> applications = applicationMapper.selectAll();
        applications.stream().filter(applicationDO -> applicationDO.getGitlabProjectId() != null)
                .forEach(applicationDO -> {
                            CheckLog checkLog = new CheckLog();
                            checkLog.setContent(APP + applicationDO.getName() + "sync gitlab commit");
                            try {
                                List<CommitDTO> commitDOS = gitlabProjectRepository.listCommits(applicationDO.getGitlabProjectId(), ADMIN, 1, 100);
                                commitDOS.forEach(commitDO -> {
                                    DevopsGitlabCommitE devopsGitlabCommitE = new DevopsGitlabCommitE();
                                    devopsGitlabCommitE.setAppId(applicationDO.getId());
                                    devopsGitlabCommitE.setCommitContent(commitDO.getMessage());
                                    devopsGitlabCommitE.setCommitSha(commitDO.getId());
                                    devopsGitlabCommitE.setUrl(commitDO.getUrl());
                                    if ("root".equals(commitDO.getAuthorName())) {
                                        devopsGitlabCommitE.setUserId(1L);
                                    } else {
                                        UserE userE = iamRepository.queryByEmail(applicationDO.getProjectId(),
                                                commitDO.getAuthorEmail());
                                        if (userE != null) {
                                            devopsGitlabCommitE.setUserId(userE.getId());
                                        }
                                    }
                                    devopsGitlabCommitE.setCommitDate(commitDO.getCommittedDate());
                                    devopsGitlabCommitRepository.baseCreate(devopsGitlabCommitE);

                                });
                                logs.add(checkLog);

                            } catch (Exception e) {
                                checkLog.setResult(FAILED + e.getMessage());
                            }
                        }
                );
    }


    private void syncPipelines(List<CheckLog> logs) {
        List<ApplicationDTO> applications = applicationMapper.selectAll();
        applications.stream().filter(applicationDO -> applicationDO.getGitlabProjectId() != null)
                .forEach(applicationDO -> {
                    CheckLog checkLog = new CheckLog();
                    checkLog.setContent(APP + applicationDO.getName() + "sync gitlab pipeline");
                    try {
                        List<GitlabPipelineE> pipelineDOS = gitlabProjectRepository
                                .listPipeline(applicationDO.getGitlabProjectId(), ADMIN);
                        pipelineDOS.forEach(pipelineE -> {
                            GitlabPipelineE gitlabPipelineE = gitlabProjectRepository
                                    .getPipeline(applicationDO.getGitlabProjectId(), pipelineE.getId(), ADMIN);
                            DevopsGitlabPipelineE devopsGitlabPipelineE = new DevopsGitlabPipelineE();
                            devopsGitlabPipelineE.setAppId(applicationDO.getId());
                            Long userId = userAttrRepository
                                    .baseQueryUserIdByGitlabUserId(TypeUtil.objToLong(gitlabPipelineE.getUser()
                                            .getId()));
                            devopsGitlabPipelineE.setPipelineCreateUserId(userId);
                            devopsGitlabPipelineE.setPipelineId(TypeUtil.objToLong(gitlabPipelineE.getId()));
                            if (gitlabPipelineE.getStatus().toString().equals(SUCCESS)) {
                                devopsGitlabPipelineE.setStatus("passed");
                            } else {
                                devopsGitlabPipelineE.setStatus(gitlabPipelineE.getStatus().toString());
                            }
                            try {
                                devopsGitlabPipelineE
                                        .setPipelineCreationDate(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                                                .parse(gitlabPipelineE.getCreatedAt()));
                            } catch (ParseException e) {
                                checkLog.setResult(FAILED + e.getMessage());
                            }
                            DevopsGitlabCommitE devopsGitlabCommitE = devopsGitlabCommitRepository
                                    .baseQueryByShaAndRef(gitlabPipelineE.getSha(), gitlabPipelineE.getRef());
                            if (devopsGitlabCommitE != null) {
                                devopsGitlabCommitE.setRef(gitlabPipelineE.getRef());
                                devopsGitlabCommitRepository.baseUpdate(devopsGitlabCommitE);
                                devopsGitlabPipelineE.initDevopsGitlabCommitEById(devopsGitlabCommitE.getId());
                            }
                            List<Stage> stages = new ArrayList<>();
                            List<String> stageNames = new ArrayList<>();
                            List<Integer> gitlabJobIds = gitlabProjectRepository
                                    .listJobs(applicationDO.getGitlabProjectId(),
                                            TypeUtil.objToInteger(devopsGitlabPipelineE.getPipelineId()), ADMIN)
                                    .stream().map(GitlabJobE::getId).collect(Collectors.toList());

                            gitlabProjectRepository
                                    .getCommitStatus(applicationDO.getGitlabProjectId(), gitlabPipelineE.getSha(),
                                            ADMIN)
                                    .forEach(commitStatuseDO -> {
                                        if (gitlabJobIds.contains(commitStatuseDO.getId())) {
                                            Stage stage = getPipelineStage(commitStatuseDO);
                                            stages.add(stage);
                                        } else if (commitStatuseDO.getName().equals(SONARQUBE) && !stageNames
                                                .contains(SONARQUBE) && !stages.isEmpty()) {
                                            Stage stage = getPipelineStage(commitStatuseDO);
                                            stages.add(stage);
                                            stageNames.add(commitStatuseDO.getName());
                                        }
                                    });
                            devopsGitlabPipelineE.setStage(JSONArray.toJSONString(stages));
                            devopsGitlabPipelineRepository.baseCreate(devopsGitlabPipelineE);
                        });
                    } catch (Exception e) {
                        checkLog.setResult(FAILED + e.getMessage());
                    }
                    logs.add(checkLog);
                });
        devopsGitlabPipelineRepository.baseDeleteWithoutCommit();
    }

    private void fixPipelines(List<CheckLog> logs) {
        List<DevopsGitlabPipelineDTO> gitlabPipelineES = devopsGitlabPipelineMapper.selectAll();
        gitlabPipelineES.forEach(devopsGitlabPipelineDO -> {
            CheckLog checkLog = new CheckLog();
            checkLog.setContent(APP + devopsGitlabPipelineDO.getPipelineId() + "fix pipeline");
            try {
                ApplicationDTO applicationDO = applicationMapper.selectByPrimaryKey(devopsGitlabPipelineDO.getAppId());
                if (applicationDO.getGitlabProjectId() != null) {
                    DevopsGitlabCommitDTO devopsGitlabCommitDO = devopsGitlabCommitMapper
                            .selectByPrimaryKey(devopsGitlabPipelineDO.getCommitId());
                    if (devopsGitlabCommitDO != null) {
                        GitlabPipelineE gitlabPipelineE = gitlabProjectRepository
                                .getPipeline(applicationDO.getGitlabProjectId(),
                                        TypeUtil.objToInteger(devopsGitlabPipelineDO.getPipelineId()), ADMIN);
                        List<Stage> stages = new ArrayList<>();
                        List<String> stageNames = new ArrayList<>();
                        List<Integer> gitlabJobIds = gitlabProjectRepository
                                .listJobs(applicationDO.getGitlabProjectId(),
                                        TypeUtil.objToInteger(devopsGitlabPipelineDO.getPipelineId()),
                                        ADMIN).stream().map(GitlabJobE::getId).collect(Collectors.toList());

                        gitlabProjectRepository.getCommitStatus(applicationDO.getGitlabProjectId(),
                                devopsGitlabCommitDO.getCommitSha(), ADMIN)
                                .forEach(commitStatuseDO -> {
                                    if (gitlabJobIds.contains(commitStatuseDO.getId())) {
                                        Stage stage = getPipelineStage(commitStatuseDO);
                                        stages.add(stage);
                                    } else if (commitStatuseDO.getName().equals(SONARQUBE) && !stageNames
                                            .contains(SONARQUBE) && !stages.isEmpty()) {
                                        Stage stage = getPipelineStage(commitStatuseDO);
                                        stages.add(stage);
                                        stageNames.add(commitStatuseDO.getName());
                                    }
                                });
                        devopsGitlabPipelineDO.setStatus(gitlabPipelineE.getStatus().toString());
                        devopsGitlabPipelineDO.setStage(JSONArray.toJSONString(stages));
                        devopsGitlabPipelineMapper.updateByPrimaryKeySelective(devopsGitlabPipelineDO);
                    }
                }
                checkLog.setResult(SUCCESS);
            } catch (Exception e) {
                checkLog.setResult(FAILED + e.getMessage());
            }
            logs.add(checkLog);
        });

    }

    private Stage getPipelineStage(CommitStatuseDTO commitStatuseDTO) {
        Stage stage = new Stage();
        stage.setDescription(commitStatuseDTO.getDescription());
        stage.setId(commitStatuseDTO.getId());
        stage.setName(commitStatuseDTO.getName());
        stage.setStatus(commitStatuseDTO.getStatus());
        if (commitStatuseDTO.getFinishedAt() != null) {
            stage.setFinishedAt(commitStatuseDTO.getFinishedAt());
        }
        if (commitStatuseDTO.getStartedAt() != null) {
            stage.setStartedAt(commitStatuseDTO.getStartedAt());
        }
        return stage;
    }


    private void syncCommandId() {
        devopsCheckLogMapper.syncCommandId();
    }

    private void syncCommandVersionId() {
        devopsCheckLogMapper.syncCommandVersionId();
    }

    private void syncGitOpsUserAccess(List<CheckLog> logs, String version) {
        List<Long> projectIds = devopsProjectMapper.selectAll().stream().
                filter(devopsProjectDO -> devopsProjectDO.getDevopsEnvGroupId() != null && devopsProjectDO
                        .getDevopsAppGroupId() != null).map(DevopsProjectDTO::getIamProjectId)
                .collect(Collectors.toList());
        projectIds.forEach(projectId -> {
            PageInfo<UserWithRoleDTO> allProjectUser = iamRepository

                    .queryUserPermissionByProjectId(projectId, new PageRequest(0, 0), false);

            if (!allProjectUser.getList().isEmpty()) {
                allProjectUser.getList().forEach(userWithRoleDTO -> {
                    // 如果是项目成员
                    if (userWithRoleDTO.getRoles().stream().noneMatch(roleDTO -> roleDTO.getCode().equals(PROJECT_OWNER))) {
                        CheckLog checkLog = new CheckLog();
                        checkLog.setContent(userWithRoleDTO.getLoginName() + ": remove env permission");
                        try {
                            UserAttrE userAttrE = userAttrRepository.baseQueryById(userWithRoleDTO.getId());
                            if (userAttrE != null) {
                                Integer gitlabUserId = TypeUtil.objToInteger(userAttrE.getGitlabUserId());
                                DevopsProjectVO devopsProjectE = devopsProjectRepository.baseQueryByProjectId(projectId);
                                GitlabMemberE envgroupMemberE = gitlabGroupMemberRepository.getUserMemberByUserId(
                                        TypeUtil.objToInteger(devopsProjectE.getDevopsEnvGroupId()), gitlabUserId);
                                GitlabMemberE appgroupMemberE = gitlabGroupMemberRepository.getUserMemberByUserId(
                                        TypeUtil.objToInteger(devopsProjectE.getDevopsAppGroupId()), gitlabUserId);
                                if (version.equals(TWELVE_VERSION)) {
                                    if (appgroupMemberE != null && appgroupMemberE.getId() != null) {
                                        gitlabGroupMemberRepository.deleteMember(
                                                TypeUtil.objToInteger(devopsProjectE.getDevopsAppGroupId()), gitlabUserId);
                                    }
                                } else {
                                    if (envgroupMemberE != null && envgroupMemberE.getId() != null) {
                                        gitlabGroupMemberRepository.deleteMember(
                                                TypeUtil.objToInteger(devopsProjectE.getDevopsEnvGroupId()), gitlabUserId);
                                    }
                                }
                            }
                            checkLog.setResult(SUCCESS);
                            LOGGER.info(SUCCESS);
                        } catch (Exception e) {
                            LOGGER.info(FAILED + e.getMessage());
                            checkLog.setResult(FAILED + e.getMessage());
                        }
                        logs.add(checkLog);
                    }
                });
            }
        });
    }

    private void syncGitlabUserName(List<CheckLog> logs) {
        userAttrRepository.baseList().stream().filter(userAttrE -> userAttrE.getGitlabUserId() != null).forEach(userAttrE ->
                {
                    CheckLog checkLog = new CheckLog();
                    try {
                        UserE userE = iamRepository.queryUserByUserId(userAttrE.getIamUserId());
                        if (Pattern.matches(SERVICE_PATTERN, userE.getLoginName())) {
                            userAttrE.setGitlabUserName(userE.getLoginName());
                            if (userE.getLoginName().equals("admin") || userE.getLoginName().equals("admin1")) {
                                userAttrE.setGitlabUserName("root");
                            }
                        } else {
                            GitlabUserE gitlabUserE = gitlabUserRepository.getGitlabUserByUserId(TypeUtil.objToInteger(userAttrE.getGitlabUserId()));
                            userAttrE.setGitlabUserName(gitlabUserE.getUsername());
                        }
                        userAttrRepository.baseUpdate(userAttrE);
                        LOGGER.info(SUCCESS);
                        checkLog.setResult(SUCCESS);
                        checkLog.setContent(userAttrE.getGitlabUserId() + " : init Name Succeed");
                    } catch (Exception e) {
                        LOGGER.info(e.getMessage());
                        checkLog.setResult(FAILED);
                        checkLog.setContent(userAttrE.getGitlabUserId() + " : init Name Failed");
                    }
                    logs.add(checkLog);
                }
        );
    }


    class UpgradeTask implements Runnable {
        private String version;
        private Long env;

        UpgradeTask(String version) {
            this.version = version;
        }


        UpgradeTask(String version, Long env) {
            this.version = version;
            this.env = env;
        }

        @Override
        public void run() {
            DevopsCheckLogDTO devopsCheckLogDTO = new DevopsCheckLogDTO();
            List<CheckLog> logs = new ArrayList<>();
            devopsCheckLogDTO.setBeginCheckDate(new Date());
            if ("0.8".equals(version)) {
                LOGGER.info("Start to execute upgrade task 0.8");
                List<ApplicationDTO> applications = applicationMapper.selectAll();
                applications.stream()
                        .filter(applicationDO ->
                                applicationDO.getGitlabProjectId() != null && applicationDO.getHookId() == null)
                        .forEach(applicationDO -> syncWebHook(applicationDO, logs));
                applications.stream()
                        .filter(applicationDO ->
                                applicationDO.getGitlabProjectId() != null)
                        .forEach(applicationDO -> syncBranches(applicationDO, logs));
            } else if ("0.9".equals(version)) {
                LOGGER.info("Start to execute upgrade task 0.9");
                syncNonEnvGroupProject(logs);
                gitOpsUserAccess();
                syncEnvProject(logs);
            } else if ("0.10.0".equals(version)) {
                LOGGER.info("Start to execute upgrade task 1.0");
                updateWebHook(logs);
                syncCommit(logs);
                syncPipelines(logs);
            } else if ("0.10.4".equals(version)) {
                fixPipelines(logs);
            } else if ("0.11.0".equals(version)) {
                syncGitOpsUserAccess(logs, "0.11.0");
                updateWebHook(logs);
            } else if (TWELVE_VERSION.equals(version)) {
                syncGitOpsUserAccess(logs, TWELVE_VERSION);
                syncGitlabUserName(logs);
            } else if ("0.11.2".equals(version)) {
                syncCommandId();
                syncCommandVersionId();
            } else if ("0.14.0".equals(version)) {
                syncDevopsEnvPodNodeNameAndRestartCount();
            } else if ("0.15.0".equals(version)) {
                syncAppToIam();
                syncAppVersion();
                syncCiVariableAndRole(logs);
            } else if ("0.17.0".equals(version)) {
                syncSonarProject(logs);
            } else if ("0.18.0".equals(version)) {
                syncDeployValues(logs);
            } else if ("0.19.0".equals(version)) {
                syncEnvAppRelevance(logs);
//                syncClusters(logs);
                syncAppShare();
            } else {
                LOGGER.info("version not matched");
            }

            devopsCheckLogDTO.setLog(JSON.toJSONString(logs));
            devopsCheckLogDTO.setEndCheckDate(new Date());

            devopsCheckLogMapper.insert(devopsCheckLogDTO);
        }

        /**
         * 为devops_env_pod表的遗留数据的新增的node_name和restart_count字段同步数据
         */
        private void syncDevopsEnvPodNodeNameAndRestartCount() {
            List<DevopsEnvPodDTO> pods = devopsEnvPodMapper.selectAll();
            pods.forEach(pod -> {
                try {
                    if (StringUtils.isEmpty(pod.getNodeName())) {
                        String message = devopsEnvResourceRepository.getResourceDetailByNameAndTypeAndInstanceId(pod.getAppInstanceId(), pod.getName(), ResourceType.POD);
                        V1Pod v1Pod = json.deserialize(message, V1Pod.class);
                        pod.setNodeName(v1Pod.getSpec().getNodeName());
                        pod.setRestartCount(K8sUtil.getRestartCountForPod(v1Pod));
                        devopsEnvPodMapper.updateByPrimaryKey(pod);
                    }
                } catch (Exception e) {
                    LOGGER.warn("Processing node name and restart count for pod with name {} failed. \n exception is: {}", pod.getName(), e);
                }
            });
        }

        /**
         * 同步devops应用表数据到iam应用表数据
         */
        @Saga(code = "devops-sync-application",
                description = "Devops同步应用到iam", inputSchema = "{}")
        private void syncAppToIam() {
            List<ApplicationDTO> applicationDOS = applicationMapper.selectAll().stream().filter(applicationDO -> applicationDO.getGitlabProjectId() != null).collect(Collectors.toList());
            List<IamAppPayLoad> iamAppPayLoads = applicationDOS.stream().map(applicationDO -> {
                ProjectVO projectE = iamRepository.queryIamProject(applicationDO.getProjectId());
                OrganizationVO organization = iamRepository.queryOrganizationById(projectE.getOrganization().getId());
                IamAppPayLoad iamAppPayLoad = new IamAppPayLoad();
                iamAppPayLoad.setOrganizationId(organization.getId());
                iamAppPayLoad.setApplicationCategory(APPLICATION);
                iamAppPayLoad.setApplicationType(applicationDO.getType());
                iamAppPayLoad.setCode(applicationDO.getCode());
                iamAppPayLoad.setName(applicationDO.getName());
                iamAppPayLoad.setEnabled(true);
                iamAppPayLoad.setProjectId(applicationDO.getProjectId());
                return iamAppPayLoad;

            }).collect(Collectors.toList());
            String input = JSONArray.toJSONString(iamAppPayLoads);
            sagaClient.startSaga("devops-sync-application", new StartInstanceDTO(input, "", "", "", null));
        }

        private void syncCiVariableAndRole(List<CheckLog> logs) {
            List<Integer> gitlabProjectIds = applicationMapper.selectAll().stream()
                    .filter(applicationDO -> applicationDO.getGitlabProjectId() != null)
                    .map(ApplicationDTO::getGitlabProjectId).collect(Collectors.toList());
            //changRole
            gitlabProjectIds.forEach(t -> {
                CheckLog checkLog = new CheckLog();
                try {
                    checkLog.setContent("gitlabProjectId: " + t + " sync gitlab variable and role");
                    List<MemberVO> memberDTOS = gitlabProjectRepository.getAllMemberByProjectId(t).stream().filter(m -> m.getAccessLevel() == 40).map(memberE ->
                            new MemberVO(memberE.getId(), 30)).collect(Collectors.toList());
                    if (!memberDTOS.isEmpty()) {
                        gitlabRepository.updateMemberIntoProject(t, memberDTOS);
                    }
                    LOGGER.info("update project member maintainer to developer success");
                    checkLog.setResult(SUCCESS);
                } catch (Exception e) {
                    LOGGER.info("gitlab.project.is.not.exist,gitlabProjectId: " + t, e);
                    checkLog.setResult(FAILED + e.getMessage());
                }
                LOGGER.info(checkLog.toString());
                logs.add(checkLog);
            });
        }

        private void syncDeployValues(List<CheckLog> logs) {
            applicationInstanceRepository.list().stream().filter(applicationInstanceE -> applicationInstanceE.getCommandId() != null).forEach(applicationInstanceE ->
                    {
                        CheckLog checkLog = new CheckLog();
                        checkLog.setContent(String.format("Sync instance deploy value of %s", applicationInstanceE.getCode()));
                        LOGGER.info("Sync instance deploy value of {}", applicationInstanceE.getCode());
                        try {
                            DevopsEnvCommandVO devopsEnvCommandE = devopsEnvCommandRepository.query(applicationInstanceE.getCommandId());
                            String versionValue = applicationVersionRepository.queryValue(devopsEnvCommandE.getObjectVersionId());
                            String deployValue = applicationInstanceRepository.queryValueByInstanceId(applicationInstanceE.getId());
                            devopsEnvCommandValueRepository.baseUpdateById(devopsEnvCommandE.getDevopsEnvCommandValueDTO().getId(), applicationInstanceService.getReplaceResult(versionValue, deployValue).getYaml());
                            checkLog.setResult("success");
                        } catch (Exception e) {
                            checkLog.setResult("fail");
                            LOGGER.info(e.getMessage(), e);
                        }
                        logs.add(checkLog);
                    }
            );
        }

        private void syncClusters(List<CheckLog> logs) {
            PageInfo<OrganizationSimplifyDTO> organizations = iamRepository.getAllOrgs(0, 0);
            organizations.getList().forEach(org -> {
                CheckLog checkLog = new CheckLog();
                checkLog.setContent(String.format("Sync organization cluster to project,organizationId: %s", org.getId()));
                LOGGER.info("Sync organization cluster to project,organizationId: {}", org.getId());
                try {
                    Long categoryId = 1L;
                    ProjectReqVO projectDTO = createOpsProject(org.getId(), categoryId);
                    clusterRepository.baseUpdateProjectId(org.getId(), projectDTO.getId());
                    checkLog.setResult("success");
                } catch (Exception e) {
                    checkLog.setResult("fail");
                    LOGGER.info(e.getMessage(), e);
                }
                logs.add(checkLog);
            });
        }

        private void syncEnvAppRelevance(List<CheckLog> logs) {
            List<DevopsEnvApplicationE> envApplicationES = applicationInstanceRepository.listAllEnvApp();

            envApplicationES.stream().distinct().forEach(v -> {
                CheckLog checkLog = new CheckLog();
                checkLog.setContent(String.format(
                        "Sync environment application relationship,envId: %s, appId: %s", v.getEnvId(), v.getAppId()));
                try {
                    devopsEnvApplicationRepostitory.baseCreate(v);
                    checkLog.setResult("success");
                } catch (Exception e) {
                    checkLog.setResult("fail");
                    LOGGER.info(e.getMessage(), e);
                }
                logs.add(checkLog);
            });
        }

        private void syncAppShare() {
            LOGGER.info("update publish level to organization.");
            appShareRepository.baseUpdatePublishLevel();
            LOGGER.info("update publish level success.");
            LOGGER.info("update publish Time.");
            applicationVersionRepository.baseUpdatePublishTime();
            LOGGER.info("update publish time success.");
        }

        private ProjectCategoryEDTO createProjectCatory(Long orgId) {
            ProjectCategoryEDTO categoryEDTO = new ProjectCategoryEDTO();
            categoryEDTO.setCode("ops-default");
            categoryEDTO.setBuiltInFlag(false);
            categoryEDTO.setDescription("运维管理项目");
            categoryEDTO.setName("运维项目");
            //todo
            List<MenuCodeDTO> list = new ArrayList<>();
            MenuCodeDTO menuCodeDTO = new MenuCodeDTO();
            list.add(menuCodeDTO);
            return orgRepository.createProjectCategory(orgId, categoryEDTO);
        }

        private ProjectReqVO createOpsProject(Long orgId, Long categoryId) {
            ProjectCreateDTO createDTO = new ProjectCreateDTO();
            List<Long> categoruIds = new ArrayList<>();
            categoruIds.add(categoryId);
            createDTO.setCategory("ops");
            createDTO.setCategoryIds(categoruIds);
            createDTO.setCode("ops-default");
            createDTO.setName("运维专用项目");
            return iamRepository.createProject(orgId, createDTO);
        }


        private void syncSonarProject(List<CheckLog> logs) {
            if (!sonarqubeUrl.isEmpty()) {
                SonarClient sonarClient = RetrofitHandler.getSonarClient(sonarqubeUrl, SONAR, userName, password);
                //将所有sonar项目设为私有
                applicationMapper.selectAll().forEach(applicationDO -> {
                    if (applicationDO.getGitlabProjectId() != null) {
                        LOGGER.info("sonar.project.privatet,applicationId:" + applicationDO.getId());
                        ProjectVO projectE = iamRepository.queryIamProject(applicationDO.getProjectId());
                        OrganizationVO organization = iamRepository.queryOrganizationById(projectE.getOrganization().getId());
                        String key = String.format("%s-%s:%s", organization.getCode(), projectE.getCode(), applicationDO.getCode());
                        Map<String, String> maps = new HashMap<>();
                        maps.put("project", key);
                        maps.put("visibility", "private");
                        try {
                            sonarClient.updateVisibility(maps).execute();
                        } catch (IOException e) {
                            LOGGER.error(e.getMessage());
                        }
                    }
                });
                try {
                    //更改默认新建项目为私有
                    Map<String, String> defaultMaps = new HashMap<>();
                    defaultMaps.put("organization", "default-organization");
                    defaultMaps.put("projectVisibility", "private");
                    sonarClient.updateDefaultVisibility(defaultMaps).execute();
                    //更改默认权限模板
                    Map<String, String> appTemplete = new HashMap<>();
                    appTemplete.put("templateId", "default_template");
                    appTemplete.put("groupName", "sonar-administrators");
                    appTemplete.put(PERMISSION, "codeviewer");
                    sonarClient.addGroupToTemplate(appTemplete).execute();
                    appTemplete.put(PERMISSION, "user");
                    sonarClient.addGroupToTemplate(appTemplete).execute();

                    Map<String, String> removeTemplete = new HashMap<>();
                    removeTemplete.put("templateId", "default_template");
                    removeTemplete.put("groupName", "sonar-users");
                    removeTemplete.put(PERMISSION, "codeviewer");
                    sonarClient.removeGroupFromTemplate(removeTemplete).execute();
                    removeTemplete.put(PERMISSION, "user");
                    sonarClient.removeGroupFromTemplate(removeTemplete).execute();
                } catch (IOException e) {
                    LOGGER.error(e.getMessage());
                }
            }
        }

        private void syncAppVersion() {
            List<ApplicationVersionDTO> applicationVersionDTOS = applicationVersionMapper.selectAll();
            if (!applicationVersionDTOS.isEmpty() && !applicationVersionDTOS.get(0).getRepository().contains(helmUrl)) {
                if (helmUrl.endsWith("/")) {
                    helmUrl = helmUrl.substring(0, helmUrl.length() - 1);
                }
                applicationVersionMapper.updateRepository(helmUrl);
            }
        }


        private void syncEnvProject(List<CheckLog> logs) {
            LOGGER.info("start to sync env project");
            List<DevopsEnvironmentE> devopsEnvironmentES = devopsEnvironmentRepository.baseListAll();
            devopsEnvironmentES
                    .stream()
                    .filter(devopsEnvironmentE -> devopsEnvironmentE.getGitlabEnvProjectId() == null)
                    .forEach(devopsEnvironmentE -> {
                        CheckLog checkLog = new CheckLog();
                        try {
                            //generate git project code
                            checkLog.setContent("env: " + devopsEnvironmentE.getName() + " create gitops project");
                            ProjectVO projectE = iamRepository.queryIamProject(devopsEnvironmentE.getProjectE().getId());
                            OrganizationVO organization = iamRepository
                                    .queryOrganizationById(projectE.getOrganization().getId());
                            //generate rsa key
                            List<String> sshKeys = FileUtil.getSshKey(String.format("%s/%s/%s",
                                    organization.getCode(), projectE.getCode(), devopsEnvironmentE.getCode()));
                            devopsEnvironmentE.setEnvIdRsa(sshKeys.get(0));
                            devopsEnvironmentE.setEnvIdRsaPub(sshKeys.get(1));
                            devopsEnvironmentRepository.baseUpdate(devopsEnvironmentE);
                            GitlabProjectPayload gitlabProjectPayload = new GitlabProjectPayload();
                            DevopsProjectVO devopsProjectE = devopsProjectRepository.baseQueryByProjectId(projectE.getId());
                            gitlabProjectPayload.setGroupId(TypeUtil.objToInteger(devopsProjectE.getDevopsEnvGroupId()));
                            gitlabProjectPayload.setUserId(ADMIN);
                            gitlabProjectPayload.setPath(devopsEnvironmentE.getCode());
                            gitlabProjectPayload.setOrganizationId(null);
                            gitlabProjectPayload.setType(ENV);
                            devopsEnvironmentService.handleCreateEnvSaga(gitlabProjectPayload);
                            checkLog.setResult(SUCCESS);
                        } catch (Exception e) {
                            LOGGER.info("create env git project error", e);
                            checkLog.setResult(FAILED + e.getMessage());
                        }
                        LOGGER.info(checkLog.toString());
                        logs.add(checkLog);
                    });
        }


        private void syncWebHook(ApplicationDTO applicationDO, List<CheckLog> logs) {
            CheckLog checkLog = new CheckLog();
            checkLog.setContent(APP + applicationDO.getName() + " create gitlab webhook");
            try {
                ProjectHookDTO projectHookDTO = ProjectHookDTO.allHook();
                projectHookDTO.setEnableSslVerification(true);
                projectHookDTO.setProjectId(applicationDO.getGitlabProjectId());
                projectHookDTO.setToken(applicationDO.getToken());
                String uri = !gatewayUrl.endsWith("/") ? gatewayUrl + "/" : gatewayUrl;
                uri += "devops/webhook";
                projectHookDTO.setUrl(uri);
                applicationDO.setHookId(TypeUtil.objToLong(gitlabRepository
                        .createWebHook(applicationDO.getGitlabProjectId(), ADMIN, projectHookDTO).getId()));
                applicationMapper.updateByPrimaryKey(applicationDO);
                checkLog.setResult(SUCCESS);
            } catch (Exception e) {
                checkLog.setResult(FAILED + e.getMessage());
            }
            logs.add(checkLog);
        }

        private void syncBranches(ApplicationDTO applicationDO, List<CheckLog> logs) {
            CheckLog checkLog = new CheckLog();
            checkLog.setContent(APP + applicationDO.getName() + " sync branches");
            try {
                Optional<List<BranchDTO>> branchDOS = Optional.ofNullable(
                        devopsGitRepository.listBranches(applicationDO.getGitlabProjectId(), ADMIN));
                List<String> branchNames =
                        devopsGitRepository.listDevopsBranchesByAppId(applicationDO.getId()).stream()
                                .map(DevopsBranchE::getBranchName).collect(Collectors.toList());
                branchDOS.ifPresent(branchDOS1 -> branchDOS1.stream()
                        .filter(branchDO -> !branchNames.contains(branchDO.getName()))
                        .forEach(branchDO -> {
                            DevopsBranchE newDevopsBranchE = new DevopsBranchE();
                            newDevopsBranchE.initApplicationE(applicationDO.getId());
                            newDevopsBranchE.setLastCommitDate(branchDO.getCommit().getCommittedDate());
                            newDevopsBranchE.setLastCommit(branchDO.getCommit().getId());
                            newDevopsBranchE.setBranchName(branchDO.getName());
                            newDevopsBranchE.setCheckoutCommit(branchDO.getCommit().getId());
                            newDevopsBranchE.setCheckoutDate(branchDO.getCommit().getCommittedDate());
                            newDevopsBranchE.setLastCommitMsg(branchDO.getCommit().getMessage());
                            UserAttrE userAttrE = userAttrRepository.baseQueryByGitlabUserName(branchDO.getCommit().getAuthorName());
                            newDevopsBranchE.setLastCommitUser(userAttrE.getIamUserId());
                            devopsGitRepository.createDevopsBranch(newDevopsBranchE);
                            checkLog.setResult(SUCCESS);
                        }));
            } catch (Exception e) {
                checkLog.setResult(FAILED + e.getMessage());
            }
            logs.add(checkLog);
        }


        @Saga(code = "devops-upgrade-0.9",
                description = "Devops平滑升级到0.9", inputSchema = "{}")
        private void gitOpsUserAccess() {
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info(" saga start");
            }
            sagaClient.startSaga("devops-upgrade-0.9", new StartInstanceDTO("{}", "", "", ResourceLevel.SITE.value(), 0L));
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info(" saga start success");
            }
        }

        private void syncNonEnvGroupProject(List<CheckLog> logs) {
            List<DevopsProjectDTO> projectDOList = devopsCheckLogMapper.queryNonEnvGroupProject();
            LOGGER.info("{} projects need to upgrade", projectDOList.size());
            final String groupCodeSuffix = "gitops";
            projectDOList.forEach(t -> {
                CheckLog checkLog = new CheckLog();
                try {
                    Long projectId = t.getIamProjectId();
                    ProjectVO projectE = iamRepository.queryIamProject(projectId);
                    checkLog.setContent("project: " + projectE.getName() + " create gitops group");
                    OrganizationVO organization = iamRepository
                            .queryOrganizationById(projectE.getOrganization().getId());
                    //创建gitlab group
                    GroupDTO group = new GroupDTO();
                    // name: orgName-projectName
                    group.setName(String.format("%s-%s-%s",
                            organization.getName(), projectE.getName(), groupCodeSuffix));
                    // path: orgCode-projectCode
                    group.setPath(String.format("%s-%s-%s",
                            organization.getCode(), projectE.getCode(), groupCodeSuffix));
                    ResponseEntity<GroupDTO> responseEntity;
                    try {
                        responseEntity = gitlabServiceClient.createGroup(group, ADMIN);
                        group = responseEntity.getBody();
                        DevopsProjectDTO devopsProjectDO = new DevopsProjectDTO(projectId);
                        devopsProjectDO.setDevopsEnvGroupId(TypeUtil.objToLong(group.getId()));
                        devopsProjectRepository.baseUpdate(devopsProjectDO);
                        checkLog.setResult(SUCCESS);
                    } catch (CommonException e) {
                        checkLog.setResult(e.getMessage());
                    }
                } catch (Exception e) {
                    LOGGER.info("create project GitOps group error");
                    checkLog.setResult(FAILED + e.getMessage());
                }
                LOGGER.info(checkLog.toString());
                logs.add(checkLog);
            });
        }
    }
}
