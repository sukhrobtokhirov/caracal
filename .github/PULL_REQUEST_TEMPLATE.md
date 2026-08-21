## What this changes

<!-- The problem first, then the approach. If it fixes an issue, "Fixes #123". -->

## How it was tested

<!-- Which commands, on which platform. `./gradlew check`, and
     `CARACAL_INTEGRATION=1 ./gradlew :core:test` where the change touches an
     engine adapter. -->

## Screenshots

<!-- For any UI change, before and after — with synthetic connection names,
     hostnames, and query text. Check the whole frame, including the title bar
     and any notification that wandered in. -->

## Checklist

- [ ] Tests were added with the change, not deferred. New `:core` behaviour is
      tested headlessly; new UI has a Compose test.
- [ ] No credential, Redis command argument, or query parameter can reach a log,
      an error message, or the query history through this change.
- [ ] Read-only and production guards are still enforced in `:core`, not in the UI.
- [ ] Result sizes, scan iterations, and value reads stay bounded.
- [ ] `:core` still has no Compose dependency (`./gradlew check` covers this).
- [ ] Documentation was updated where behaviour changed — README, the milestone
      guide under `docs/mvp-steps/`, or CONTRIBUTING.
- [ ] No deferred scope crept in: no new engine, no grid editing, no network
      listener, no telemetry.
