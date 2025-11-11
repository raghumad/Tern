# Route Planner Implementation Plan

**Last Updated**: November 2025
**Status**: Code quality cleanup complete ✅ - ready for route planner development
**Strategy**: Clean up "slop" incrementally, validate each change, then resume route planner features

## Route Planner Development

### Phase 2: Establish Route Baseline ✅
- [x] Revert all local changes to establish clean baseline
- [x] Test compilation and device installation from clean state
- [x] Push clean baseline to GitHub as checkpoint

### Phase 3: Architecture Cleanup ✅
- [x] **Remove incorrect route planner implementation** (RouteOverlayManager, WaypointStore, etc.)
- [x] **Clean MapViewContainer** (remove route-specific UI components)
- [x] **Clean MapViewModel** (remove RouteOverlayManager references)
- [x] **Test compilation** (ensure core map functionality works)
- [x] **Establish clean baseline** (ready for proper route planner implementation)

### Phase 4: Proper Route Planner Implementation (Planning Phase)
- [ ] **Discuss RouteCache architecture** (flat buffer storage, Hilbert indexing)
- [ ] **Plan Route-centric data flow** (Route.addWaypoint() → RouteCache.persistRoute())
- [ ] **Design waypoint UX patterns** (iOS-style long press + drag)
- [ ] **Implement RouteCache** (mimic AirspaceCache patterns)
- [ ] **Add route visualization** (polylines, waypoint markers)
- [ ] **Test on device** and push to GitHub
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
