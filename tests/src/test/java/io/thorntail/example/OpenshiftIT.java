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

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.WebDriverWait;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.startsWith;

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

        driver.get(jaegerQuery + "/api/traces?service=istio-ingressgateway&start=" + startTime);
        driver.findElement(By.className("btn-primary")).submit();
        driver.findElement(By.id("inputUsername")).sendKeys("developer");
        driver.findElement(By.id("inputPassword")).sendKeys("developer");
        driver.findElement(By.className("btn-primary")).submit();

        try {
            WebElement approve = driver.findElement(By.name("approve"));
            approve.submit();

        } catch (NoSuchElementException|IllegalStateException ex) {
            System.out.println("NOT FOUND");
        }

        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
            driver.get(jaegerQuery + "/api/traces?service=istio-ingressgateway&start=" + startTime);
            JsonObject jsonObject = new JsonParser().parse(driver.getPageSource()).getAsJsonObject();
            assertThat(jsonObject).isNotNull();

            JsonElement jsonElement = jsonObject.get("data");
            assertThat(jsonElement).isNotNull().satisfies(c -> c.isJsonArray());
            JsonArray data = jsonElement.getAsJsonArray();
            assertThat(data).isNotNull().isNotEmpty();

            List<String> serviceNames = data.get(0).getAsJsonObject()
                    .get("processes").getAsJsonObject()
                    .entrySet()
                    .stream().map(c -> c.getValue().getAsJsonObject().get("serviceName").toString())
                    .collect(Collectors.toList());
            assertThat(serviceNames)
                    .isNotEmpty()
                    .filteredOn(s -> s.contains("thorntail"))
                    .haveAtLeastOne(isApplicationService("greeting"))
                    .haveAtLeastOne(isApplicationService("cute-name"));
        });
    }

    private Condition<String> isApplicationService(String name) {
        return new Condition<>(s -> s.contains(name), "a trace named: " + name);
    }
}