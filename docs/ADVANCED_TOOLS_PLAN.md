# MeowMeow advanced features — separate development track

**Branch:** `feat/advanced-tools`  
**Base:** `main` (independent of `feat/file-tools-v0-2`).  
**Status:** Reserved for advanced work; the user has not defined the feature list yet. This is **not** a declaration that any advanced feature is implemented or approved.

## Goal

Keep future complex/experimental functionality out of the core v0.2 file-discovery and file-operations branch. Develop it independently so the stable `main` branch is never blocked by advanced experiments.

## Ground rules

- Begin with user-approved acceptance criteria for each proposed advanced capability; do not silently expand the product scope.
- Keep this branch and its PR as **draft** until there is code, automated verification and on-device QA.
- Avoid a dependency on the other feature branch. If advanced work needs shared utilities, merge a reviewed foundational change to `main` first, then rebase/merge `main` into this branch.
- Prioritize privacy, storage permission boundaries, predictable performance and cancellation for any future scans or indexing.
- Destructive advanced actions are opt-in, previewed and confirmed. No auto cleanup.
- No claims that a feature is available until explicitly tested.

## To define later

- [ ] Proposed feature(s) and desired UX confirmed by user.
- [ ] Scope and Android/SAF compatibility documented.
- [ ] Safety/privacy and performance budgets reviewed.
- [ ] Implementation, tests and device QA completed.
- [ ] Merge reviewed separately into `main`.
