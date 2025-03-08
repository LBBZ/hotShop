package com.real.common.util;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PageHelperUtils<T> {

    public PageInfo<T> getPageInfo(int pageNum, int pageSize, List<T> list) {
        try (Page<Object> ignored = PageHelper.startPage(pageNum, pageSize)) {
            return new PageInfo<>(list);
        }
    }

}
