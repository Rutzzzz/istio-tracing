/*
 *  Copyright 2018 Red Hat, Inc, and individual contributors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package io.thorntail.example;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import org.arquillian.cube.istio.api.IstioResource;
import org.arquillian.cube.openshift.impl.enricher.AwaitRoute;
import org.arquillian.cube.openshift.impl.enricher.RouteURL;
import org.assertj.core.api.Condition;
import org.jboss.arquillian.drone.api.annotation.Drone;
import org.jboss.arquillian.junit.Arquillian;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertTrue;

@RunWith(Arquillian.class)
@IstioResource("classpath:istio-gateway.yaml")
public class OpenshiftIT {
    private static final String ISTIO_NAMESPACE = "istio-system";
    private static final String JAEGER_QUERY_NAME = "jaeger";
    private static final String ISTIO_INGRESS_GATEWAY_NAME = "istio-ingressgateway";

    @RouteURL(value = JAEGER_QUERY_NAME, namespace = ISTIO_NAMESPACE)
    private String jaegerQuery;

    @RouteURL(value = ISTIO_INGRESS_GATEWAY_NAME, path = "/thorntail-istio-tracing", namespace = ISTIO_NAMESPACE)
    @AwaitRoute
    private String ingressGateway;

    @Drone
    private WebDriver driver;

    private WebDriverWait wait;

    @Before
    public void init() {
        wait = new WebDriverWait(driver, 5);
    }

    @Test
    public void tracingTest() {
        long startTime = TimeUnit.MILLISECONDS.toMicros(System.currentTimeMillis())
                - TimeUnit.SECONDS.toMicros(1);

        given()
                .baseUri(ingressGateway)
                .when()
                .get("/api/greeting")
                .then()
                .statusCode(200)
                .body("content", startsWith("Hello"));

        driver.get(jaegerQuery + "/api/traces?service=istio-ingressgateway");
        driver.findElement(By.className("btn-primary")).submit();
        driver.findElement(By.id("inputUsername")).sendKeys("developer");
        driver.findElement(By.id("inputPassword")).sendKeys("developer");
        driver.findElement(By.className("btn-primary")).submit();
        driver.get(jaegerQuery + "/api/traces?service=istio-ingressgateway");
        JsonObject jsonObject = new JsonParser().parse(driver.getPageSource()).getAsJsonObject();

        List<String> serviceNames = new ArrayList<>();
        for (JsonElement e :jsonObject.get("data").getAsJsonArray()) {
            JsonObject processes = e.getAsJsonObject().get("processes").getAsJsonObject();
            Set<Map.Entry<String, JsonElement>> entries = processes.entrySet();
            serviceNames.addAll(entries.stream().map(c -> c.getValue().getAsJsonObject().get("serviceName").toString())
                                        .collect(Collectors.toList()));
        }
        assertTrue(serviceNames.stream().anyMatch(c -> c.contains("thorntail") && c.contains("greeting")));
        assertTrue(serviceNames.stream().anyMatch(c -> c.contains("thorntail") && c.contains("cute-name")));
    }


//    @Test
//    public void tracingTest() {
//        long startTime = TimeUnit.MILLISECONDS.toMicros(System.currentTimeMillis())
//                - TimeUnit.SECONDS.toMicros(1); // tolerate 1 sec of skew between localhost and Minishift VM
//
//        given()
//                .baseUri(ingressGateway)
//        .when()
//                .get("/api/greeting")
//        .then()
//                .statusCode(200)
//                .body("content", startsWith("Hello"));
//
//        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
//            Map<String, Map> processes =
//                    given()
//                            .baseUri(jaegerQuery)
//                            .relaxedHTTPSValidation()
//                    .when()
//                            .param("service", ISTIO_INGRESS_GATEWAY_NAME)
//                            .param("start", startTime)
//                            .get("/api/traces")
//                    .then()
//                            .statusCode(200)
//                            .body("data", notNullValue())
//                            .body("data[0]", notNullValue())
//                            .body("data[0].processes", notNullValue())
//                            .extract()
//                            .jsonPath()
//                            .getMap("data[0].processes", String.class, Map.class);
//
//            assertThat(processes.values())
//                    .isNotEmpty()
//                    .extracting("serviceName", String.class)
//                    .filteredOn(s -> s.contains("thorntail"))
//                    .haveAtLeastOne(isApplicationService("greeting"))
//                    .haveAtLeastOne(isApplicationService("cute-name"));
//        });
//    }
//
//    private Condition<String> isApplicationService(String name) {
//        return new Condition<>(s -> s.contains(name), "a trace named: " + name);
//    }
}