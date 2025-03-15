package com.real.portal;

import java.net.URL;

public class ResourceValidator {
    public static void main(String[] args) {
        // 检查配置文件是否存在
        URL dbConfig = ResourceValidator.class.getResource("/config/common-db.yml");
        System.out.println("DB配置路径: " + (dbConfig != null ? dbConfig.getPath() : "未找到"));
    }
}