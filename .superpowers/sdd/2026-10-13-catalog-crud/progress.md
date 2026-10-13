# SDD ledger — plan: docs/superpowers/plans/2026-10-13-catalog-crud.md

Pre-flight: cross-task interface rows —
- T1→T2..T7: ResourceNotFoundException (common/exception) consumed by all services — T1 produces, no conflict.
- T1→T2..T7: PageResponse.from(Page, List) consumed by all list methods — T1 produces, no conflict.
- T1→T2..T7: WebConfig pageable customizer (fallback 0/20, max 100) — global, no conflict.
- T2→T3: CategoryRepository.findByCategoryId consumed by ProductService — exists, no conflict.
- T4→T6/T7: VariantRepository.findByVariantId consumed by MediaService + junction — exists, no conflict.
- T5→T7: AttributeRepository + AttributeValueRepository finders consumed by junctions — exist, no conflict.
- T7 adds findByVariantValueId/findByProductValueId to junction repos — new methods, no conflict with existing.
- Self-consistency: all 8 task briefs' tests match their Produces blocks; media update immutable documented; junction no-update asserted. Clean.

Task 1: complete (commits 7fbd2d4..7b7039a, tests: CatalogExceptionHandlerTest 6/6 pass)
Task 1: Ruling: WebMvcTest import is org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest — Boot 4.x repackaged slices, verified in jar — cost if wrong: none, compiles green.
Task 1: Ruling: direct handler unit tests instead of MockMvc slice — slice advice not registered plus security chain 401s; unit tests prove same mapping — cost if wrong: advice-wiring gap, covered in Task 8 smoke.

Task 2: complete (commits 7b7039a..0468733, tests: CategoryServiceTest 5/5 + CatalogExceptionHandlerTest 6/6 = 11/11 pass)

Task 3: complete (commits 0468733..057a486, tests: ProductServiceTest 4/4 pass)

Task 4: complete (commits 057a486..70d2e7a, tests: VariantServiceTest 4/4 pass)
Task 4: Ruling: nested GET /api/products/{productId}/variants hosted on VariantController with absolute paths (no class-level RequestMapping) — absolute @GetMapping values ignore class mapping; keeping both routes on one controller instead of splitting — cost if wrong: none, verified at smoke.

Task 5: complete (commits 70d2e7a..e360c6f, tests: AttributeServiceTest 4/4 pass)

Task 6: complete (commits e360c6f..8b3558b, tests: MediaServiceTest 4/4 pass)

Task 7: complete (commits 8b3558b..df85838, tests: JunctionServiceTest 4/4 pass)

Task 8: complete (full suite 54/54 green across 18 test classes; dev smoke: category list paginated + GET one + POST/DELETE roundtrip + 404/409/400 envelopes all verified live, server stopped after)

Review: complete (0 critical, 5 important — all fixed in d292bbf).
Fixes: media scoped GET routes added; scoped lists 404 on missing parent (variant/attribute-value/media/junctions); update DTOs no longer expose status/deleted (DELETE-only transitions); handler returns generic 409/500 messages + logs detail; new NoResourceFound(404)+NotReadable(400) mappings; service-level media exactly-one guard.
Deferred (minor, accepted): N+1 on lists fine at size<=100; stale List<> finders remain; absolute-path route style kept; junction single-GET additive per plan; PUT==PATCH null-ignore per plan; permitAll until JWT lands.
Final: full suite 62/62 green across 18 test classes.
Smoke re-verify (d292bbf): ember-tetra/variants live 200 with 1 row; products/ember-tetra/media 200 empty page; scoped 404 on nope-xyz; generic 409 on dup slug moss; generic 400 on malformed JSON (client quoting fixed); unknown route 404. Server stopped. Branch feature/PIM/phat at d292bbf, .superpowers/ untracked (gitignored).
