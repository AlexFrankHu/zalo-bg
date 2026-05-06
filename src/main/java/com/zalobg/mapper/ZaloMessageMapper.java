package com.zalobg.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zalobg.entity.ZaloMessage;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

public interface ZaloMessageMapper extends BaseMapper<ZaloMessage> {

    /**
     * 按 (owner_zalo_id, peer_user_id) 维度统计消息总数.
     * 仅统计一对一聊天 (groupId IS NULL OR groupId=0). 已逻辑删除的消息会被自动过滤
     * (deleted=0). 返回每行包含 peer_user_id 和 cnt 两列.
     */
    @Select({"<script>",
            "SELECT peer_user_id AS peer_user_id, COUNT(*) AS cnt",
            "  FROM zalo_message",
            " WHERE owner_zalo_id = #{ownerZaloId}",
            "   AND deleted = 0",
            "   AND (group_id IS NULL OR group_id = 0)",
            "   AND peer_user_id IN",
            "   <foreach item='p' collection='peerUserIds' open='(' separator=',' close=')'>#{p}</foreach>",
            " GROUP BY peer_user_id",
            "</script>"})
    List<Map<String, Object>> selectMessageTotalsByOwnerAndPeers(
            @Param("ownerZaloId") Long ownerZaloId,
            @Param("peerUserIds") List<Long> peerUserIds);
}
