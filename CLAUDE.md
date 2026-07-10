# Apitomy Axiom

## API-First Development

This project follows **contract-first** (API-first) development for the REST layer:

1. **OpenAPI spec first** — All REST API changes start in
   `common/api/src/main/resources/openapi.json`.
2. **Generate JAX-RS interfaces** — Run `mvn install` (or `build.sh`) to generate Java interfaces
   and bean classes from the spec into `common/api/target/generated-sources/jaxrs/`.
3. **Implement the interface** — REST resource classes in `app/src/main/java/.../rest/` must
   `implement` the generated interface (e.g., `McpServersResourceImpl implements McpResource`).
   Never add `@Path` annotations directly on impl classes — paths come from the generated
   interface.
4. **Use generated beans** — Request and response types come from the generated beans in
   `io.apitomy.axiom.api.beans`. Do not use raw `JsonNode` for request/response bodies in REST
   resource methods.

See `docs/developer-guide/api-first-development.md` for the full guide.
