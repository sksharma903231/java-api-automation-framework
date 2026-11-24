# Java API Automation Testing Framework

[![Java 21](https://img.shields.io/badge/Java-21%20LTS-orange.svg)](https://www.oracle.com/java/technologies/javase/jdk21-archive-downloads.html)
[![Maven](https://img.shields.io/badge/Maven-3.9%2B-blue.svg)](https://maven.apache.org/)
[![REST Assured](https://img.shields.io/badge/REST%20Assured-5.5.0-green.svg)](https://rest-assured.io/)
[![TestNG](https://img.shields.io/badge/TestNG-7.10.2-red.svg)](https://testng.org/)
[![Allure](https://img.shields.io/badge/Allure-2.29.0-yellow.svg)](https://allurereport.org/)
[![CI](https://img.shields.io/badge/CI%2FCD-GitHub%20Actions-blueviolet.svg)](https://github.com/features/actions)

A modular, robust, and maintainable REST API automation testing framework built with **Java 21**, **REST Assured**, **TestNG**, **Jackson**, **Allure**, and **Log4j2**. Designed to demonstrate modern SDET architectural standards, strict data protection, schema validation, data-driven testing, and static analysis enforcement.

Target API: [DummyJSON](https://dummyjson.com) (E-Commerce REST API).

---

## Table of Contents

- [Architectural Overview](#architectural-overview)
- [Technology Stack](#technology-stack)
- [Project Directory Structure](#project-directory-structure)
- [Core Engineering Highlights](#core-engineering-highlights)
  - [1. Sensitive Data Redaction & Secure Observability](#1-sensitive-data-redaction--secure-observability)
  - [2. Stateless Client & Thread-Safe Concurrency](#2-stateless-client--thread-safe-concurrency)
  - [3. DTO Design & Modern Java 21 Features](#3-dto-design--modern-java-21-features)
  - [4. Layered Separation of Concerns](#4-layered-separation-of-concerns)
- [Test Strategy & Coverage](#test-strategy--coverage)
  - [Test Suite Breakdown](#test-suite-breakdown)
  - [JSON Schema Validation](#json-schema-validation)
  - [Data-Driven Testing Matrix](#data-driven-testing-matrix)
- [Static Code Analysis & Quality Gates](#static-code-analysis--quality-gates)
- [Configuration & Environment Overrides](#configuration--environment-overrides)
- [Getting Started & Local Execution](#getting-started--local-execution)
- [Reporting & Diagnostics](#reporting--diagnostics)
- [Continuous Integration (GitHub Actions)](#continuous-integration-github-actions)
- [Target API Nuances & Design Decisions](#target-api-nuances--design-decisions)
- [Future Enhancements](#future-enhancements)

---

## Architectural Overview

The framework employs a strict layered architecture with loose coupling and high cohesion. No business logic leaks into the transport layer, and no test assertions reside in service classes.

```mermaid
flowchart TD
    subgraph Test_Layer["Test Layer (TestNG + AssertJ)"]
        TestClasses["Service Tests<br/>(Auth, User, Product, Cart)"]
        SchemaTests["JSON Schema<br/>Validation Tests"]
        CoreTests["Client, Config &<br/>Security Unit Tests"]
        DataProvider["Data Providers<br/>(External JSON Files)"]
    end

    subgraph Service_Layer["Service Layer (Business Operations)"]
        AuthSvc["AuthService"]
        UserSvc["UserService"]
        ProdSvc["ProductService"]
        CartSvc["CartService"]
    end

    subgraph Model_Layer["Model Layer (Jackson DTOs)"]
        Records["Immutable Records<br/>(LoginRequest)"]
        POJOs["Standard Jackson JavaBean DTOs<br/>(User, Product, Cart, etc.)"]
    end

    subgraph Core_Layer["Core Framework Layer"]
        ApiClient["ApiClient<br/>(Stateless HTTP Execution)"]
        ReqFactory["RequestSpecFactory<br/>(Base URI, Timeouts, Auth)"]
        RespFactory["ResponseSpecFactory<br/>(Common Validations)"]
        ConfigMgr["ConfigManager<br/>(Strict RFC Validation & Env Overrides)"]
    end

    subgraph Security_Layer["Security & Observability"]
        RedactFilter["SensitiveDataRedactionFilter<br/>(Jackson AST Traversal & Safe Header Allowlist)"]
        Log4j2["Log4j2 Logger<br/>(Redacted HTTP Wire Log)"]
        AllureReport["Allure Report<br/>(Redacted Request/Response Attachments)"]
    end

    subgraph External_Target["Target REST API"]
        TargetAPI["DummyJSON Endpoints<br/>(https://dummyjson.com)"]
    end

    DataProvider --> TestClasses
    TestClasses --> Service_Layer
    SchemaTests --> Service_Layer
    CoreTests --> Core_Layer
    Service_Layer --> Model_Layer
    Service_Layer --> ApiClient
    ApiClient --> ReqFactory
    ReqFactory --> ConfigMgr
    ApiClient --> RedactFilter
    RedactFilter --> Log4j2
    RedactFilter --> AllureReport
    ApiClient --> External_Target
```

---

## Technology Stack

All dependencies and plugins are pinned to stable versions compatible with Java 21 LTS:

| Component | Library / Tool | Version | Purpose |
|---|---|---|---|
| **Language** | Java (JDK) | 21 (LTS) | Modern language runtime (Records, Text Blocks, Pattern Matching) |
| **Build Tool** | Apache Maven | 3.9+ | Build management, dependency resolution, test lifecycle |
| **HTTP Client** | REST Assured | 5.5.0 | Fluent REST API test harness |
| **Test Runner** | TestNG | 7.10.2 | Suite orchestration, parallelization, `@DataProvider` support |
| **Serialization** | Jackson Databind | 2.18.2 | JSON to strongly-typed Java DTO binding and AST parsing |
| **Assertions** | AssertJ | 3.27.3 | Fluent, readable assertions with descriptive failure messages |
| **Schema Validation** | JSON Schema Validator | 5.5.0 | REST Assured draft-04/07 schema compliance checking |
| **Reporting** | Allure TestNG | 2.29.0 | Interactive execution reporting with metadata and attachments |
| **Logging** | Log4j2 + SLF4J | 2.24.3 / 2.0.16 | Structured file and console logging |
| **Code Style** | Checkstyle Maven Plugin | 3.6.0 | Strict enforcement of SDET code quality conventions |
| **Static Analysis** | SpotBugs Maven Plugin | 4.8.6.6 | Bytecode analysis for security and concurrency vulnerabilities |
| **CI/CD** | GitHub Actions | v4 | Automated pull request validation, test execution, report publishing |

---

## Project Directory Structure

```
api-automation-framework/
├── .github/
│   └── workflows/
│       └── api-tests.yml                       # GitHub Actions CI/CD regression pipeline
├── logs/
│   └── api-automation.log                      # Live execution log (redacted)
├── src/
│   ├── main/
│   │   ├── java/com/sksharma/api/
│   │   │   ├── clients/
│   │   │   │   ├── ApiClient.java              # Stateless HTTP execution client
│   │   │   │   ├── RequestSpecFactory.java     # RequestSpecification factory with timeouts
│   │   │   │   └── ResponseSpecFactory.java    # Reusable ResponseSpecifications
│   │   │   ├── config/
│   │   │   │   └── ConfigManager.java          # Thread-safe configuration manager
│   │   │   ├── constants/
│   │   │   │   ├── EndpointConstants.java      # Centralized API URI constants
│   │   │   │   └── HeaderConstants.java        # Standardized HTTP headers
│   │   │   ├── filters/
│   │   │   │   └── SensitiveDataRedactionFilter.java # Recursive JSON masking & header allowlist
│   │   │   ├── models/
│   │   │   │   ├── auth/
│   │   │   │   │   ├── LoginRequest.java       # Java 21 Record for authentication
│   │   │   │   │   └── LoginResponse.java      # Auth tokens and user profile DTO
│   │   │   │   ├── carts/
│   │   │   │   │   ├── Cart.java               # Single cart model
│   │   │   │   │   ├── CartItem.java           # Line item DTO
│   │   │   │   │   └── CartResponse.java       # Paginated cart response DTO
│   │   │   │   ├── products/
│   │   │   │   │   ├── Product.java            # Product Jackson DTO with getters & setters
│   │   │   │   │   └── ProductResponse.java    # Paginated product response DTO
│   │   │   │   └── users/
│   │   │   │       ├── User.java               # User profile Jackson DTO with getters & setters
│   │   │   │       └── UserResponse.java       # Paginated user response DTO
│   │   │   └── services/
│   │   │       ├── AuthService.java            # Authentication operations
│   │   │       ├── CartService.java            # Cart query & manipulation operations
│   │   │       ├── ProductService.java         # Product catalog operations
│   │   │       └── UserService.java            # User management operations
│   │   └── resources/
│   │       ├── config.properties               # Default environment configuration
│   │       └── log4j2.xml                      # Log4j2 async & file appender configuration
│   └── test/
│       ├── java/com/sksharma/api/tests/
│       │   ├── clients/
│       │   │   └── ApiClientTest.java          # HTTP client, concurrency & redaction tests
│       │   ├── config/
│       │   │   └── ConfigManagerTest.java      # Configuration loading & URL validation tests
│       │   ├── filters/
│       │   │   └── SensitiveDataRedactionFilterTest.java # Sanitization unit tests
│       │   ├── listeners/
│       │   │   ├── TestExecutionListener.java  # TestNG lifecycle listener & sanitized Allure attachment
│       │   │   └── TestExecutionListenerSecurityTest.java # Exception secret leak prevention tests
│       │   ├── schemas/
│       │   │   └── JsonSchemaValidationTest.java # JSON Schema validation tests
│       │   ├── services/
│       │   │   ├── AuthServiceTest.java        # Authentication tests (positive & negative)
│       │   │   ├── CartServiceTest.java        # Cart lifecycle & data-driven tests
│       │   │   ├── ProductServiceTest.java     # Product catalog & search tests
│       │   │   └── UserServiceTest.java        # User query, mutation & boundary tests
│       │   └── utils/
│       │       ├── TestCredentials.java        # Secure credential loader
│       │       └── TestDataLoader.java         # Jackson external test data deserializer
│       └── resources/
│           ├── demo-credentials.properties     # Demo account credentials
│           ├── schemas/                        # JSON Schema draft definitions
│           │   ├── cart-list-schema.json
│           │   ├── cart-schema.json
│           │   ├── login-response-schema.json
│           │   ├── product-list-schema.json
│           │   ├── product-schema.json
│           │   ├── user-list-schema.json
│           │   └── user-schema.json
│           ├── testdata/                       # External test dataset files
│           │   ├── invalid-credentials.json
│           │   ├── product-searches.json
│           │   └── user-boundary-ids.json
│           └── testng.xml                      # Master TestNG test execution suite
├── checkstyle.xml                              # Checkstyle ruleset
├── pom.xml                                     # Maven POM with pinned versions & plugins
├── spotbugs-exclude.xml                        # SpotBugs exclusions for Jackson DTOs
└── README.md                                   # Comprehensive documentation
```

---

## Core Engineering Highlights

### 1. Sensitive Data Redaction & Secure Observability
A common security flaw in automation frameworks is leaking sensitive credentials (passwords, JWTs, API keys) into log files or HTML test reports (e.g., Allure).

- **Jackson AST Traversal & Complete Container Redaction**: `SensitiveDataRedactionFilter` parses request and response JSON payloads into Jackson `JsonNode` trees. When a sensitive key (`password`, `accessToken`, `refreshToken`, `clientSecret`, `apiKey`, `token`, `credentials`) is encountered, the entire value—whether a primitive, an object (e.g., `credentials: { value: "secret" }`), or an array—is completely replaced with `[REDACTED]`. Non-sensitive container nodes are recursively traversed to sanitize nested sensitive fields.
- **Query Parameter Stripping**: Query parameters are omitted from path logging to prevent token leakage via GET request URLs (e.g., `/search?apiKey=xyz` is sanitized to `/search`).
- **Header Allowlist**: Only explicitly whitelisted diagnostic headers (`Content-Type`, `Accept`, `Date`, `Server`, `ETag`, `cf-ray`) are emitted; `Authorization` headers are masked as `Bearer [REDACTED]`.
- **Exception & Listener Sanitization**: `TestExecutionListener` intercepts test failures and skips, sanitizing exception messages via regex and Jackson redaction before logging. Unrestricted full stack traces are withheld from Allure attachments, providing concise, sanitized diagnostic context instead.
- **Synthetic Redaction Verification**: Tests in `ApiClientTest` and `TestExecutionListenerSecurityTest` inject synthetic query parameters, Bearer tokens, payload secrets, and exception secrets into live requests and failure handlers, verifying synthetic secret redaction across tested logging and reporting paths (without claiming unverified complete security coverage).
- **Fail-Safe Shielding**: Malformed or non-JSON payloads are masked with `[Non-JSON or Unparseable Payload]` rather than logged verbatim. Normal request execution is wrapped in try-catch guards catching only expected exceptions so logging/reporting failures never disrupt test execution or mask JVM errors.

### 2. Stateless Client & Thread-Safe Concurrency
- `ApiClient` is completely stateless. It does not store per-request mutable state or response objects in instance fields.
- **REST Assured Base URI Guard**: REST Assured's `RequestSpecBuilder.build()` defaults `baseUri` to `http://localhost:8080`. `ApiClient.prepareRequest()` explicitly re-applies the validated `ConfigManager.getBaseUrl()` to ensure consistent routing without cross-thread interference.
- **Thread Safety Verification**: Verified via a 5-thread concurrent execution test using `CountDownLatch` and `Callable` tasks in `ApiClientTest`, confirming that concurrent requests through shared client instances execute independently without race conditions or cross-thread request contamination.

### 3. DTO Design & Modern Java 21 Features
- **Java 21 Records**: Request contracts with fixed, mandatory schemas like `LoginRequest` are implemented as immutable records (`public record LoginRequest(String username, String password) {}`).
- **Standard Jackson JavaBean DTOs**: Entity models (`User`, `Product`, `Cart`, `CartItem`) are implemented as standard, transparent JavaBeans with getters and setters conforming to standard Jackson databind conventions (not withers or fluent mutators).
- **Resilient Serialization**: Models use `@JsonIgnoreProperties(ignoreUnknown = true)` to ensure resilience against unexpected upstream schema additions.

### 4. Layered Separation of Concerns
- **Service Layer**: Classes like `ProductService` encapsulate HTTP endpoint paths, payload wrapping, and method calls. They return raw REST Assured `Response` objects.
- **Test Layer**: Assertion logic, status code checks, schema validations, and business invariants remain exclusively inside `@Test` methods using AssertJ.

---

## Test Strategy & Coverage

The test suite consists of **99 distinct test methods**, producing **108 individual test executions** across multiple datasets (verified via Surefire reports). Tests are strictly separated into **offline** and **integration** execution profiles:

- **Offline Profile (`-Poffline`)**: 59 distinct test methods, 59 executions (100% deterministic local execution, zero external network requests).
- **Integration Profile (`-Pintegration`)**: 40 distinct test methods, 49 executions (requires internet connectivity and DummyJSON availability).
- **Complete Suite (`mvn clean verify`)**: 99 distinct test methods, 108 executions (runs all offline and integration suites).

### Test Suite Breakdown

| Test Class | Domain | Profile | Test Methods | Executions | Key Behaviors Validated |
|---|---|:---:|:---:|:---:|---|
| [`AuthServiceTest`](src/test/java/com/sksharma/api/tests/services/AuthServiceTest.java) | Authentication | `integration` | 5 | 8 | Valid demo login, token validation, data-driven invalid credentials (empty user, empty pass, invalid pass, non-existent user), token-based protected profile fetch, missing Authorization header rejection (401), expired/invalid token handling |
| [`UserServiceTest`](src/test/java/com/sksharma/api/tests/services/UserServiceTest.java) | User Management | `integration` | 6 | 8 | Get all users, pagination structure, get user by ID, user search query, user creation (POST 201), user update (PUT 200), boundary ID testing (min, max, out-of-bounds) |
| [`ProductServiceTest`](src/test/java/com/sksharma/api/tests/services/ProductServiceTest.java) | Products | `integration` | 8 | 10 | Get all products, get product by ID, invalid product ID (404), category filtering, data-driven search queries (phone, laptop, watch), non-matching search query returning empty list (total: 0), product creation, full update (PUT), simulated deletion (DELETE) |
| [`CartServiceTest`](src/test/java/com/sksharma/api/tests/services/CartServiceTest.java) | Carts & Orders | `integration` | 7 | 9 | Get all carts, get cart by ID, non-existent cart 404, data-driven user carts query, add cart with items (POST 201), cart update (PUT 200), cart deletion (DELETE 200) |
| [`JsonSchemaValidationTest`](src/test/java/com/sksharma/api/tests/schemas/JsonSchemaValidationTest.java) | Schema Validation | Mixed (8 `int`, 1 `off`) | 9 | 9 | Draft-07 JSON Schema validation against live API responses for Auth, User, Product, Cart; negative contract validation with deliberately invalid payload |
| [`ApiClientTest`](src/test/java/com/sksharma/api/tests/clients/ApiClientTest.java) | Core HTTP Client | Mixed (6 `int`, 2 `off`) | 8 | 8 | GET, POST, PUT, PATCH, DELETE operations, 5-thread concurrent execution safety with request-response identity matching, runtime config override, live log/Allure secret leak verification |
| [`ApiClientOfflineTest`](src/test/java/com/sksharma/api/tests/clients/ApiClientOfflineTest.java) | Offline HTTP Client | `offline` | 23 | 23 | Ephemeral local server testing: all HTTP methods, query params, custom headers, JSON body, Content-Type/Accept, URL construction, HTTP 200/201/204/400/401/404/500, timeouts, malformed JSON, empty bodies, HTML error pages, connection failure, 10-thread concurrency |
| [`ConfigManagerTest`](src/test/java/com/sksharma/api/tests/config/ConfigManagerTest.java) | Configuration | `offline` | 12 | 12 | Property file loading, system property precedence, fallbacks, type parsing, scheme validation (rejects ftp), relative URI rejection, userinfo rejection, query parameter rejection, fragment rejection, timeout bounds (1–300s) |
| [`SensitiveDataRedactionFilterTest`](src/test/java/com/sksharma/api/tests/filters/SensitiveDataRedactionFilterTest.java) | Security | `offline` | 20 | 20 | Query parameter omission, nested JSON AST redaction, container/object redaction, null value handling, casing variations, non-JSON shielding, Bearer token variations, encoded query strings (%26 and %3D values, literal & boundaries, nested queries, mixed encoding, empty values), exception messages with nested JSON/URL credentials, kebab-case, snake_case, and camelCase key-value and URL query parameter sanitization |
| [`TestExecutionListenerSecurityTest`](src/test/java/com/sksharma/api/tests/listeners/TestExecutionListenerSecurityTest.java) | Listener Security | `offline` | 1 | 1 | Verifies that synthetic exception secrets (passwords, tokens, query params) do not leak into application logs (asserting on newly appended bytes) and that newly generated Allure failure diagnostic attachments are created and sanitized |
| **Total** | **10 Test Suites** | — | **99** | **108** | **108 / 108 PASS (100%)** |

### JSON Schema Validation
Schema definitions reside under `src/test/resources/schemas/`:
- `login-response-schema.json`: Validates access/refresh tokens, numeric ID, and user profile fields.
- `user-schema.json` & `user-list-schema.json`: Validates single and paginated user profiles.
- `product-schema.json` & `product-list-schema.json`: Validates product fields, positive prices, inventory counts.
- `cart-schema.json` & `cart-list-schema.json`: Validates cart calculations, product arrays, and discounted totals.

### Data-Driven Testing Matrix
External test files under `src/test/resources/testdata/`:
- `invalid-credentials.json`: Tests 4 distinct error conditions with exact expected HTTP status codes and API error messages.
- `user-boundary-ids.json`: Boundary analysis covering valid lower bound (`id: 1`), upper bound (`id: 208`), and extreme out-of-range (`id: 99999`).
- `product-searches.json`: Keyword verification testing matched results (`phone`, `laptop`, `watch`) and zero-result handling (`xyznonexistentkeyword999`).

---

## Static Code Analysis & Quality Gates

The framework enforces zero-tolerance quality gates bound directly to the Maven build lifecycle:

### Checkstyle
- **Lifecycle Binding**: Bound to the `validate` phase (`<goal>check</goal>`).
- **Ruleset**: `checkstyle.xml`.
- **Machine-Readable Report**: `target/checkstyle-result.xml`.
- **Enforcement**: Configured with `<failOnViolation>true</failOnViolation>` and `<failsOnError>true</failsOnError>`.
- **Negative Verification**: Verified that deliberately introducing checkstyle violations causes `mvn clean verify` to fail immediately.
- **Status**: **0 Violations**.

### SpotBugs
- **Lifecycle Binding**: Bound to the `verify` phase (`<goal>check</goal>`).
- **Ruleset & Exclusions**: `spotbugs-exclude.xml` (restricted to specific Jackson collection DTOs requiring direct List binding, avoiding broad package exclusions).
- **Machine-Readable Report**: `target/spotbugsXml.xml`.
- **Enforcement**: Configured with `<failOnError>true</failOnError>` and `<effort>Max</effort>`.
- **Negative Verification**: Verified that deliberately introducing unwritten fields or mutable representation exposure causes `mvn clean verify` to fail.
- **Status**: **0 Bugs / Warnings**.

---

## Configuration & Environment Overrides

Configuration is managed by `ConfigManager` following a strict precedence hierarchy:
1. **Java System Properties** (`-Dbase.url=...`) — *Highest Precedence*
2. **Environment Variables**
3. **`config.properties` File** — *Default Fallback*

### Configuration Properties

| Key | Default Value | Description |
|---|---|---|
| `base.url` | `https://dummyjson.com` | Base target API URL (strictly validated) |
| `http.timeout.seconds` | `10` | HTTP connection and socket timeout (bounded: 1–300s) |
| `logging.include.body` | `false` | When `true`, logs redacted JSON request and response bodies |

### Strict URL Validation Rules
To prevent SSRF and accidental misconfigurations, `ConfigManager` rejects:
- Non-HTTP/HTTPS schemes (e.g., `ftp://...`, `file://...`).
- Relative URIs (e.g., `/api/v1`).
- Embedded credentials (e.g., `https://user:pass@host`).
- Embedded query parameters or URI fragments in `base.url`.

---

## Getting Started & Local Execution

### Prerequisites
- **JDK 21** installed and configured (`java -version`).
- **Apache Maven 3.9+** installed (`mvn -version`).

### Clone & Build
```bash
git clone https://github.com/sksharma/api-automation-framework.git
cd api-automation-framework
```

### Run Full Test Suite & Quality Checks
```bash
# Run Checkstyle, SpotBugs, and complete TestNG test suite (108 executions)
mvn clean verify
```

### Run Offline Test Suite (Zero External Dependencies)
```bash
# Run deterministic local client, security, config, and schema tests (59 executions)
mvn clean verify -Poffline
```

### Run Integration Test Suite (DummyJSON API)
```bash
# Run live service and contract integration tests (49 executions)
mvn clean verify -Pintegration
```

### Run Static Analysis Independently
```bash
# Checkstyle code convention audit
mvn checkstyle:check

# SpotBugs static bytecode analysis
mvn compile spotbugs:check
```

### Run with Runtime Configuration Overrides
```bash
# Target a staging environment with increased timeout
mvn test -Dbase.url=https://staging.dummyjson.com -Dhttp.timeout.seconds=20
```

---

## Reporting & Diagnostics

### 1. Allure Interactive Report
Allure captures test lifecycles, `@Epic`, `@Feature`, `@Story`, `@Severity` annotations, and sanitized HTTP request/response attachments.

```bash
# Generate HTML report
mvn allure:report

# Launch Allure local web server to view interactive report
mvn allure:serve
```

The generated HTML report will open in your default browser at `http://localhost:<port>`.

### 2. Application Execution Logs
Structured application logs are written to `logs/api-automation.log` and the standard console. All query parameters, authorization headers, and body secrets are automatically masked:

```text
2026-11-22 17:00:00.123 [main] INFO  c.s.a.f.SensitiveDataRedactionFilter - HTTP REQUEST: POST https://dummyjson.com/auth/login
2026-11-22 17:00:00.124 [main] INFO  c.s.a.f.SensitiveDataRedactionFilter - Request Headers: [Content-Type=application/json, Authorization=Bearer [REDACTED]]
2026-11-22 17:00:00.125 [main] INFO  c.s.a.f.SensitiveDataRedactionFilter - Request Body: {"username":"emilys","password":"[REDACTED]"}
2026-11-22 17:00:00.456 [main] INFO  c.s.a.f.SensitiveDataRedactionFilter - HTTP RESPONSE: 200 OK (332 ms)
2026-11-22 17:00:00.457 [main] INFO  c.s.a.f.SensitiveDataRedactionFilter - Response Headers: [Content-Type=application/json; charset=utf-8]
2026-11-22 17:00:00.458 [main] INFO  c.s.a.f.SensitiveDataRedactionFilter - Response Body: {"id":1,"username":"emilys","accessToken":"[REDACTED]","refreshToken":"[REDACTED]"}
```

---

## Continuous Integration (GitHub Actions)

The framework includes a fully automated GitHub Actions pipeline in [`.github/workflows/api-tests.yml`](.github/workflows/api-tests.yml):

- **Triggers**: Pushes and Pull Requests targeting `main` or `master` branches, plus manual `workflow_dispatch`.
- **Environment**: Ubuntu Linux with Eclipse Temurin JDK 21 and Maven caching.
- **Workflow Steps**:
  1. Check out repository code.
  2. Set up JDK 21 with automatic Maven dependency caching.
  3. Execute `mvn clean verify --batch-mode` (runs Checkstyle, SpotBugs, and all TestNG tests).
  4. Generate Allure Report (`mvn allure:report`).
  5. Archive Test Execution Artifacts (`target/surefire-reports/`, `logs/`, Checkstyle/SpotBugs reports) with 14-day retention.
  6. Archive Allure HTML Report with 14-day retention.

---

## Target API Nuances & Design Decisions

### Simulated Persistence in DummyJSON
DummyJSON is a mock/simulated REST API service:
- **Write Operations (POST, PUT, PATCH, DELETE)**: Simulated write requests return HTTP 200/201 responses containing modified DTOs and assigned IDs. However, **DummyJSON does not persist these changes to a backend database**.
- **Design Decision**: Tests for create, update, and delete validate the HTTP contract, returned status code, and response payload structure. Tests avoid chaining subsequent GET calls expecting persisted modifications, as DummyJSON will return 404 for newly created simulated IDs.

### Authentication Behavior
- DummyJSON requires both `username` and `password`. If either is empty, it returns `HTTP 400 Bad Request` with `{"message": "Username and password required"}`.
- If credentials do not match public demo records, it returns `HTTP 400 Bad Request` with `{"message": "Invalid credentials"}`.
- The framework's data-driven negative test suite accurately models and validates these exact error responses.

---

## Future Enhancements

- **Dynamic Token Rotation & Refresh Handler**: Add automatic token refresh interceptor when integrating against live enterprise OAuth2 / OpenID Connect authorization servers.
- **Contract Testing with Pact**: Introduce consumer-driven contract verification alongside JSON Schema validation.
- **Multi-Environment Matrix CI**: Run tests across staging, pre-production, and production mock environments via GitHub Actions matrix strategies.
- **Containerized Test Execution**: Provide a Dockerfile and docker-compose setup for fully containerized local test runs.

---

## Author & License

Developed as a modern SDET reference framework.  
Licensed under the Apache 2.0 License.
