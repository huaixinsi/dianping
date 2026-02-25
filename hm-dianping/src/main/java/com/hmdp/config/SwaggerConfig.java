package com.hmdp.config;

import com.github.xiaoymin.knife4j.spring.annotations.EnableKnife4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import springfox.documentation.builders.ApiInfoBuilder;
import springfox.documentation.builders.PathSelectors;
import springfox.documentation.builders.RequestHandlerSelectors;
import springfox.documentation.oas.annotations.EnableOpenApi;
import springfox.documentation.service.ApiInfo;
import springfox.documentation.service.Contact;
import springfox.documentation.spi.DocumentationType;
import springfox.documentation.spring.web.plugins.Docket;

/**
 * Swagger + Knife4j 配置类
 */
@Configuration
@EnableOpenApi  // 开启Swagger3（Springfox 3.0+）
@EnableKnife4j  // 开启Knife4j增强
public class SwaggerConfig {

    /**
     * 创建Docket对象，配置Swagger核心参数
     */
    @Bean
    public Docket createRestApi() {
        return new Docket(DocumentationType.OAS_30)  // 指定Swagger3.0规范
                .apiInfo(apiInfo())                 // 配置文档基本信息
                .select()
                // 扫描指定包下的接口（替换成你的实际接口包路径）
                .apis(RequestHandlerSelectors.basePackage("com.example.demo.controller"))
                .paths(PathSelectors.any())         // 匹配所有路径
                .build()
                .enable(true);                      // 是否启用（生产环境可设为false）
    }

    /**
     * 配置文档的基本信息（标题、描述、作者等）
     */
    private ApiInfo apiInfo() {
        return new ApiInfoBuilder()
                .title("XX项目接口文档")                // 文档标题
                .description("XX项目的所有接口说明，基于Knife4j增强")  // 文档描述
                .version("1.0.0")                     // 版本号
                // 联系人信息（姓名、网址、邮箱）
                .contact(new Contact("开发者", "https://xxx.com", "xxx@xxx.com"))
                .build();
    }
}