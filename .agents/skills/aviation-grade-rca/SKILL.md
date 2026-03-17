---
name: Aviation-Grade Root Cause Analysis (RCA)
description: A protocol for identifying high-risk patterns and addressing the fundamental cause of regressions using the 5 Whys methodology.
---

# 🕵️ Aviation-Grade Root Cause Analysis (RCA)

In aviation, a single failure is often the result of a chain of events. We apply the **5 Whys** methodology to look beyond the immediate symptom and identify the **High-Risk Architectural Pattern** that allowed the bug to exist.

## 🚀 The Protocol: The 5 Whys

When a regression or high-risk bug is identified (especially during stabilization), perform a structured analysis:

1. **The Symptom**: What exactly failed? (e.g., "Airspaces missing from map")
2. **Why 1**: Immediate cause (e.g., "Query returned 0 results")
3. **Why 2**: Dependency failure (e.g., "Downloader cancelled prematurely")
4. **Why 3**: Process/Lifecycle failure (e.g., "Transient coroutine scope was used for long-running query")
5. **Why 4**: Structural blind spot (e.g., "Managers don't have a reliable way to survive map-panning scope changes")
6. **Why 5 (The Root)**: Architectural flaw (e.g., "The system lacked a 'Shutdown' handshake between the Map lifecycle and the OverlayCoordinator")

## ⚠️ Identifying "High-Risk Patterns"

An RCA is **Mandatory** when a bug fits any of these patterns:
- **Lifecycle Leaks**: Objects surviving beyond their intended scope (e.g., "Ghost" managers).
- **Concurrency Race Conditions**: Shared state being mutated or cancelled by overlapping async tasks.
- **Source of Truth Contiguity**: State being "split" between two components with no synchronization loop.
- **Resource Exhaustion**: Memory pressure or GC spikes caused by redundant background queries.

## 🛠️ From Analysis to Action

An RCA is not complete until it results in one of the following:
1. **Architectural Guardrail**: A structural change (e.g., `idempotent` flags, `onCleared()` hooks) that makes the failure impossible by design.
2. **Skill Update**: Codifying the learning into the project's [Aviation-Grade DoD](file:///home/raghu/src/Tern/.agents/skills/aviation-grade-dod/SKILL.md).
3. **Diagnostic Instrumentation**: Adding high-fidelity logging (e.g., "Airspace sync: Desired=X, Current=Y") to verify the fix in the field.

---
> [!IMPORTANT]
> **If a pilot can't see the fix, it hasn't been validated. Every corrective action must be backed by a high-fidelity pilot narrative in the instrumented test suite.**
