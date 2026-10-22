package com.sakuya.notesatwill.entity.VO;

import java.util.List;

public record CategoryTreeVO (
        Long id,
        Long parentId,
        String name,
        String icon,
        String type,
        Integer sort,
        //暴露给前端作为是否能被删的判断
        boolean isPreset,
        List<CategoryTreeVO> children){}
