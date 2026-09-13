# Iteration 79 — Windows Spring wiring startup hotfix

Date: 2026-09-13

## Trigger

Real Windows startup of the Iteration 78 candidate failed before acceptance because Spring could not instantiate `ContentIndexingQueueService` and attempted a missing default constructor.

## Root cause

Iteration 78 retained the four-dependency production constructor and added/used a five-argument deterministic-test constructor. With multiple constructors and no explicit injection annotation, Spring did not infer the production constructor and fell back to no-arg instantiation.

## Fix

- add `@Autowired` to the four-dependency production constructor;
- retain the package-private deterministic-test constructor;
- add `ContentIndexingQueueServiceSpringWiringTest`, which creates the service through `AnnotationConfigApplicationContext`.

## Validation

- queue + Spring wiring: 5/5 PASS;
- Application: 285 tests, 0 failures, 0 errors, 1 skip;
- Bootstrap: 17/17 PASS;
- Architecture: 14/14 PASS.

## External acceptance impact

The source change creates a new candidate. Iteration 78 candidate-bound evidence must not be reused. MHL-010/011/012/017/018/019 remain OPEN_EXTERNAL until rerun for the Iteration 79 SHA.
