# Route Planner Implementation Plan

**Last Updated**: November 2025
**Status**: Code quality cleanup in progress - addressing technical debt before route planner development
**Strategy**: Clean up "slop" incrementally, validate each change, then resume route planner features

## Code Quality Cleanup (Priority: Complete Before Route Features)

### Phase 1A: MapViewContainer.kt Refactoring
**Goal:** Break down massive 200+ line composable into focused components

- [x] **Clean up imports** → Remove unused imports, organize remaining ones
- [x] **Extract magic numbers** → Create named constants for padding, timeouts, margins
- [ ] **Extract permission handling** → Create `LocationPermissionHandler.kt`
- [ ] **Extract gesture detection** → Create `MapGestureHandler.kt`
- [ ] **Extract waypoint creation logic** → Create `WaypointCreationManager.kt`
- [ ] **Standardize error handling** → Consistent try-catch patterns
- [ ] **Simplify main composable** → Focus only on composition and state observation
- [ ] **Test compilation** after each change
- [ ] **Validate functionality** - ensure waypoint creation still works

### Phase 1B: MapViewModel.kt Refactoring
**Goal:** Simplify over-engineered 400+ line class

- [ ] **Extract overlay coordination** → Separate `OverlayCoordinator` class
- [ ] **Extract Redux integration** → Create `ReduxMapBridge.kt`
- [ ] **Remove dead code** → Delete commented-out methods and unused variables
- [ ] **Standardize logging** → Consistent log levels, remove commented logs
- [ ] **Extract constants** → Replace magic numbers with named constants
- [ ] **Simplify initialization** → Remove complex late-binding patterns
- [ ] **Test compilation** after each change
- [ ] **Validate functionality** - ensure map operations still work

### Phase 1C: General Codebase Cleanup
**Goal:** Consistent quality across all files

- [ ] **Standardize error handling** → Consistent try-catch patterns across all files
- [ ] **Remove unused imports** → Clean up all files
- [ ] **Extract magic numbers** → Replace with named constants
- [ ] **Fix formatting inconsistencies** → Standardize indentation and spacing
- [ ] **Add missing documentation** → Document complex methods
- [ ] **Standardize logging patterns** → Consistent levels and formats
- [ ] **Test compilation** after each change

## Route Planner Development (After Cleanup Complete)

### Phase 2: Establish Route Baseline ✅
- [x] Revert all local changes to establish clean baseline
- [x] Test compilation and device installation from clean state
- [x] Push clean baseline to GitHub as checkpoint

### Phase 3: Critical Architecture Fixes ✅
- [x] Move ReduxLocationService to separate file (ReduxLocationService.kt)
- [x] Remove duplicate waypoint creation (WaypointStore vs RouteStore)
- [x] Remove Redux dependencies from RouteOverlayManager until Phase 2
- [x] Simplify waypoint creation logic (remove unnecessary operations)
- [x] Move waypoint operations to Dispatchers.IO for aviation safety
- [x] Test basic waypoint creation functionality (Ready for user testing)
- [x] Push working baseline to GitHub (after compilation test)

### Phase 4: Enhanced Route Features
- [ ] Implement basic long-press waypoint creation (1-2 files max)
- [ ] Test waypoint creation on device and push to GitHub
- [ ] Add waypoint type selection UI (TURNPOINT, LAUNCH, LANDING)
- [ ] Test type selection on device and push to GitHub

### Phase 5: Route Management
- [ ] Implement simple route creation and waypoint assignment
- [ ] Test route creation on device and push to GitHub
- [ ] Add visual route display with basic polylines
- [ ] Test route visualization on device and push to GitHub
- [ ] Add route management UI (list, edit, delete routes)
- [ ] Test route management on device and push to GitHub

### Phase 6: Persistence & Advanced Features
- [ ] Implement route persistence across app restarts
- [ ] Test persistence on device and push to GitHub
- [ ] Add route editing (drag waypoints, reorder)
- [ ] Test route editing on device and push to GitHub
- [ ] Optimize for aviation safety (Dispatchers.IO, performance)
- [ ] Final validation and prepare for Phase 2 Redux migration

## Architecture Guidelines

### ✅ DO (Allowed During Development)
- Use ViewModel-based state management for new features
- Direct overlay manipulation in ViewModels during development
- Simple state management patterns for rapid prototyping
- Direct UI component to overlay manager communication during development

### ❌ AVOID (Production Patterns)
- Release features with non-Redux state management
- Skip Redux migration after development phase
- Complex business logic in UI components
- Direct database/network access from UI components

## Aviation Safety Standards

- **Performance**: <10 Redux dispatches/sec, <75% memory usage
- **Safety**: Zero visual discontinuity during flight operations
- **No Regression**: Existing functionality unaffected
- **UI Responsiveness**: No blocking operations on main thread

## Quality Gates

### Before Any GitHub Push
- ✅ **Zero compilation warnings**
- ✅ **Functionality works on device**
- ✅ **Manual testing passed**
- ✅ **No regressions in existing features**
- ✅ **Aviation safety standards maintained**

### During Development
1. **Make minimal changes** (1-3 files maximum)
2. **Test on device immediately** after each change
3. **Fix any issues** before adding new functionality
4. **Maintain aviation safety standards**
5. **Ensure zero warnings** before proceeding

## Related Documentation

- **[route_planner.md](docs/route_planner.md)**: Technical architecture and implementation strategy
- **[AGENTS.md](AGENTS.md)**: Redux patterns, overlay architecture, and aviation safety standards
- **[ARCHITECTURE_DECISIONS.md](docs/ARCHITECTURE_DECISIONS.md)**: System architecture and caching patterns
- **`.clinerules/MEMORY_BANK.md`**: Project knowledge and architecture patterns
- **`.clinerules/RULES_BANK.md`**: Development guidelines and mandatory rules

## Resume Instructions

If resuming after failure:
1. Check current git status and recent commits
2. Review this todo.md for last completed step
3. Start from the next pending item in the checklist
4. Test compilation before making any changes
5. Follow the incremental approach: implement → test → push → repeat

---
**Ready for incremental implementation!** 🪂
