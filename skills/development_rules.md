# Codebase & Development Rules

This file outlines the rules, structures, and standards that must be adhered to when developing for the FraudShield project.

## 1. Backend Standards (Spring Boot)
* **Package Structure**:
  * Controllers: `com.fraudshield.backend.controller`
  * Services: `com.fraudshield.backend.service`
  * Repositories: `com.fraudshield.backend.repository`
  * Models: `com.fraudshield.backend.model`
* **Dependency Injection**: Always use constructor injection or field injection (`@Autowired`) consistently.
* **REST APIs**:
  * Use proper HTTP verbs (`GET` for fetching, `POST` for creating, `PUT`/`PATCH` for updates, `DELETE` for deleting).
  * API endpoints should be prefixed with `/api`.
  * Return `ResponseEntity<T>` with appropriate status codes.

## 2. Frontend Standards (Vite + React + TS)
* **Styling**: Use Vanilla CSS as defined in the rules, keeping the UI clean, modern, and beautiful. Avoid TailwindCSS unless explicitly requested.
* **Components**: Keep components reusable, small, and structured under a clear directory hierarchy.
* **TypeScript**: Enforce strict type definitions for all state, props, and API response objects.

## 3. Database Rules (MongoDB)
* Do not write hardcoded credentials in the code; read them from environment variables or Spring application properties.
* Ensure indexes are created for performance critical fields (e.g. `transactionId` and `accountId` on the `transactions` and `fraud_cases` collections).

## 4. AI & Gemini Integration Rules
* Use the official Google API clients or direct HTTP APIs for calling Gemini models.
* Prompt engineering must be clean, deterministic (as much as possible), and return structured/parsable output (like JSON) wherever feasible.
