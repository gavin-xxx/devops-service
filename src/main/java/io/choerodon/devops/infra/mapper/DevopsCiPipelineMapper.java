package io.choerodon.devops.infra.mapper;

import java.util.List;
import java.util.Set;

import org.apache.ibatis.annotations.Param;

import io.choerodon.devops.api.vo.DevopsCiPipelineVO;
import io.choerodon.devops.infra.dto.DevopsCiPipelineDTO;
import io.choerodon.mybatis.common.BaseMapper;

/**
 * 〈功能简述〉
 * 〈Ci流水线Mapper〉
 *
 * @author wanghao
 * @since 2020/4/2 18:01
 */
public interface DevopsCiPipelineMapper extends BaseMapper<DevopsCiPipelineDTO> {

    /**
     * 查询项目下流水线集合
     */
    List<DevopsCiPipelineVO> queryByProjectIdAndName(@Param("projectId") Long projectId,
                                                     @Param("appServiceIds") Set<Long> appServiceIds,
                                                     @Param("name") String name);

    /**
     * 根据id查询流水线（包含关联应用服务name,gitlab_project_id）
     */
    DevopsCiPipelineVO queryById(@Param("ciPipelineId") Long ciPipelineId);

    /**
     * 停用流水线
     */
    int disablePipeline(@Param("ciPipelineId") Long ciPipelineId);

    /**
     * 启用流水线
     */
    int enablePipeline(@Param("ciPipelineId") Long ciPipelineId);

    /**
     * 根据token查询流水线
     *
     * @param token 流水线的token
     * @return 流水线数据
     */
    DevopsCiPipelineDTO queryByToken(@Param("token") String token);
}
