/* Track scene work by rendered pose, not merely by newly allocated feed arrays. */
var transformWork = {rootWrites: 0, rootSkips: 0, instanceMatrices: 0, instanceUploads: 0, instanceFrameSkips: 0};
var groupIndexCache = new WeakMap();
var unitScale = [1, 1, 1];
function poseValuesEqual(a, b) {
    if (a === b) return true;
    if (!a || !b || a.length !== b.length) return false;
    for (var i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
    return true;
}
function rootPoseEqual(a, b) {
    return !!a && !!b && poseValuesEqual(a.pos, b.pos) && poseValuesEqual(a.rot, b.rot)
        && poseValuesEqual(a.scale || unitScale, b.scale || unitScale);
}
function rootNeedsUpdate(entry, from, to, alpha) {
    var state = entry.rootPoseState;
    if (!state) state = entry.rootPoseState = {};
    var sameInput = state.mesh === entry.mesh && state.from === from && state.to === to;
    var stationary = sameInput ? state.stationary : rootPoseEqual(from, to);
    var skip = state.mesh === entry.mesh && (
        (sameInput && (stationary || state.alpha === alpha))
        || (stationary && state.settled && rootPoseEqual(state.to, to)));
    state.mesh = entry.mesh; state.from = from; state.to = to;
    state.stationary = stationary; state.alpha = alpha;
    state.settled = stationary || alpha === 1;
    transformWork[skip ? "rootSkips" : "rootWrites"]++;
    return !skip;
}
function instancePosesEqual(a, b, count) {
    if (!a || !b || a.length < count || b.length < count) return false;
    if (a === b) return true;
    for (var i = 0; i < count; i++) if (!poseValuesEqual(a[i], b[i])) return false;
    return true;
}
function instanceFrameNeeded(state, source, target, count, alpha) {
    var sameInput = state.poseSource === source && state.poseTarget === target && state.poseCount === count;
    var stationary = sameInput ? state.poseStationary : instancePosesEqual(source, target, count);
    var skip = (sameInput && (state.poseAlpha === alpha || (stationary && state.poseSettled)))
        || (stationary && state.poseSettled && state.poseSettledCount === count
            && instancePosesEqual(state.poseSettledTarget, target, count));
    state.poseSource = source; state.poseTarget = target; state.poseCount = count;
    state.poseStationary = stationary; state.poseAlpha = alpha;
    if (skip) {
        if (stationary) state.poseSettledTarget = target;
        transformWork.instanceFrameSkips++;
    }
    return !skip;
}
function instancePoseUnchanged(state, index, source, target) {
    if (!state.writtenPoses || !state.writtenPoses[index] || !poseValuesEqual(source, target)) return false;
    var base = index * 10;
    for (var i = 0; i < 10; i++) if (state.poseValues[base + i] !== target[i]) return false;
    return true;
}
function rememberInstancePose(state, index, position, rotation, scale) {
    if (!state.poseValues) {
        state.poseValues = new Float64Array(state.capacity * 10);
        state.writtenPoses = new Uint8Array(state.capacity);
    }
    var base = index * 10, values = state.poseValues;
    values[base] = position.x; values[base + 1] = position.y; values[base + 2] = position.z;
    values[base + 3] = rotation.x; values[base + 4] = rotation.y; values[base + 5] = rotation.z; values[base + 6] = rotation.w;
    values[base + 7] = scale.x; values[base + 8] = scale.y; values[base + 9] = scale.z;
    state.writtenPoses[index] = 1;
    transformWork.instanceMatrices++;
}
function finishInstanceFrame(state, target, count, alpha, first, last) {
    state.poseSettled = state.poseStationary || alpha === 1;
    if (state.poseSettled) { state.poseSettledTarget = target; state.poseSettledCount = count; }
    if (last < first) return;
    var attribute = state.mesh.instanceMatrix;
    if (attribute.updateRange) {
        var start = first * 16, end = (last + 1) * 16;
        if (attribute.updateRange.count > 0) {
            start = Math.min(start, attribute.updateRange.offset);
            end = Math.max(end, attribute.updateRange.offset + attribute.updateRange.count);
        }
        attribute.updateRange.offset = start;
        attribute.updateRange.count = end - start;
    }
    attribute.needsUpdate = true;
    transformWork.instanceUploads++;
}
