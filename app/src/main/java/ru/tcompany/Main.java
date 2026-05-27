package ru.tcompany;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
@RestController
public class Main {

    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static final String ANSI_GREEN = "\u001B[32m";
    public static final String ANSI_RESET = "\u001B[00m";

    public static void main(String[] args) {
        SpringApplication.run(Main.class, args);
        logger.info(ANSI_GREEN + "=== The DevOps web application has started successfully! ===" + ANSI_RESET);
    }

    @GetMapping("/")
    public String home() {
        logger.info("Received a request at the root endpoint [/]");
        return "<h1>Hello! The web application for the DevOps task is working correctly.</h1>" +
                "<p>Try going to <a href='/api?name=Miguel'>/api?name=Miguel</a> to generate more logs.</p>";
    }

    @GetMapping("/api")
    public String api(@RequestParam(value = "name", defaultValue = "Guest") String name) {
        logger.warn("Incoming request to the API. Looking for data for user: {}", name);
        return String.format("{\"status\": \"success\", \"message\": \"Hello %s, processing your DevOps request\"}", name);
    }
}
