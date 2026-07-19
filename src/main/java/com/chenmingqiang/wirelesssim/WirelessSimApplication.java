package com.chenmingqiang.wirelesssim;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 无线通信仿真实验平台的Java进程入口。
 *
 * <p>启动后，Spring会从本包向下扫描Controller、Service、Mapper和配置类，
 * 创建所需Bean，并启动内嵌Web服务器。</p>
 */
// @SpringBootApplication组合了配置类、自动配置和组件扫描三项能力。
@SpringBootApplication
public class WirelessSimApplication {

    /**
     * JVM调用的主方法。
     *
     * @param args 启动命令传入的参数，例如--server.port=8081
     */
    public static void main(String[] args) {
        // 创建Spring容器、加载application.yml，并启动内嵌Tomcat。
        SpringApplication.run(WirelessSimApplication.class, args);
    }
}
