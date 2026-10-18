// Modified by Addis Ortho Trading PLC, 2026.
// *************************************************************************************************
// Ray casting
// *************************************************************************************************
struct Ray {
    vec3 origin;
    vec3 direction;
    vec3 invDirection;
    int sign[3];
};

float dithering(vec2 uv) {
    return fract(sin(uv.x * 12.9898 + uv.y * 78.233) * 43758.5453);
}

float guassianFilter(vec3 uvw, float delta) {
    float dX = delta;
    float dY = delta;
    float dZ = delta;
    float pix = getNormalizedWindowLevel(uvw);
    pix += getNormalizedWindowLevel(uvw + vec3(+ dX, + dY, + dZ));
    pix += getNormalizedWindowLevel(uvw + vec3(+ dX, + dY, -dZ));
    pix += getNormalizedWindowLevel(uvw + vec3(+ dX, -dY, + dZ));
    pix += getNormalizedWindowLevel(uvw + vec3(+ dX, -dY, -dZ));
    pix += getNormalizedWindowLevel(uvw + vec3(-dX, + dY, + dZ));
    pix += getNormalizedWindowLevel(uvw + vec3(-dX, + dY, -dZ));
    pix += getNormalizedWindowLevel(uvw + vec3(-dX, -dY, + dZ));
    pix += getNormalizedWindowLevel(uvw + vec3(-dX, -dY, -dZ));
    return pix / 9;
}

// Computes a simplified lighting equation
vec3 blinnPhong(vec3 N, vec3 V, vec3 L, int lightIndex, float pixelValue, vec3 diffuse) {
    // Material properties
    vec3 Ka = defaultAmbient;
    vec3 Kd = diffuse;
    vec3 Ks = defaultSpecular;

    // Diffuse coefficient
    float diff_coeff = max(dot(L, N), 0.0);

    // Specular coefficient
    vec3 H = normalize(L + V);
    float spec_coeff = diff_coeff > 0.0 ? pow(max(dot(H, N), 0.0), lights[lightIndex].specularPower) : 0.0;


    vec4 light = texture(lightingMap, vec2(pixelValue, 0.0));
    return Ka * light.x + Kd * light.y * diff_coeff + Ks * light.z * spec_coeff;
}

// On-the-fly gradient approximation.
vec3 gradient(vec3 uvw, float delta) {
    vec3 pix1;
    pix1.x = getNormalizedWindowLevel(uvw - vec3(delta, 0, 0)) - getNormalizedWindowLevel(uvw + vec3(delta, 0, 0));
    pix1.y = getNormalizedWindowLevel(uvw - vec3(0, delta, 0)) - getNormalizedWindowLevel(uvw + vec3(0, delta, 0));
    pix1.z = getNormalizedWindowLevel(uvw - vec3(0, 0, delta)) - getNormalizedWindowLevel(uvw + vec3(0, 0, delta));
    return normalize(pix1);
}

// Shading normal for smoothShading, pointing outwards like gradient(). Central differences one
// voxel apart along each axis, each taken at the four half-voxel diagonal offsets across it, so the
// trilinear filter folds a [1 2 1] weighting over the 3x3 neighbours in as a Sobel operator does.
// The differences are scaled by the voxel spacing, so a thick-slice volume is not shaded as if its
// slices were as thin as its pixels.
vec3 smoothGradient(vec3 uvw) {
    vec3 voxel = 1.0 / vec3(textureSize(volTexture, 0));
    vec3 g = vec3(0.0);
    for (int i = 0; i < 4; ++i) {
        vec2 o = vec2((i & 1) == 0 ? -0.5 : 0.5, (i & 2) == 0 ? -0.5 : 0.5);
        vec3 ox = vec3(voxel.x, o.x * voxel.y, o.y * voxel.z);
        vec3 oy = vec3(o.x * voxel.x, voxel.y, o.y * voxel.z);
        vec3 oz = vec3(o.x * voxel.x, o.y * voxel.y, voxel.z);
        g.x += getNormalizedWindowLevel(uvw - ox) - getNormalizedWindowLevel(uvw + vec3(ox.x, -ox.yz));
        g.y += getNormalizedWindowLevel(uvw - oy) - getNormalizedWindowLevel(uvw + vec3(-oy.x, oy.y, -oy.z));
        g.z += getNormalizedWindowLevel(uvw - oz) - getNormalizedWindowLevel(uvw + vec3(-oz.xy, oz.z));
    }
    // Voxel spacing in world units is texelSize * voxel (up to a common factor).
    g /= texelSize * voxel;
    float len = length(g);
    return len > 0.0 ? g / len : vec3(0.0);
}

// Opacity of one sample once corrected to the distance between samples, with one in-plane voxel as
// the unit the preset opacities are given for (as VTK's scalar opacity unit distance).
float correctedOpacity(float alpha, vec3 stepPos) {
    vec3 voxel = 1.0 / vec3(textureSize(volTexture, 0));
    float steps = length(stepPos * texelSize) / (texelSize.x * voxel.x);
    return 1.0 - pow(1.0 - min(alpha, 0.9999), steps);
}

Ray makeRay(vec3 origin, vec3 direction) {
    vec3 inv_direction = vec3(1.0) / direction;

    return Ray(origin, direction, inv_direction, int[3](((inv_direction.x < 0.0) ? 1 : 0), ((inv_direction.y < 0.0) ? 1 : 0), ((inv_direction.z < 0.0) ? 1 : 0)));
}

Ray CreateCameraRay(vec2 uv) {
    // Transform the camera origin to world space
    vec3 origin = (viewMatrix * vec4(0.0f, 0.0f, 0.0f, 1.0f)).xyz;

    // Invert the perspective projection of the view-space position
    vec3 direction = (projectionMatrix * vec4(uv, 0.0f, 1.0f)).xyz;
    // Transform the direction from camera to world space and normalize
    direction = (viewMatrix * vec4(direction, 0.0f)).xyz;
    direction = normalize(direction);
    return makeRay(origin, direction);
}

float getAabb(float lo, float hi, int dir) {
    return dir == 0 ? lo : hi;
}

// *************************************************************************************************
// Crosshair cut — mirrors the legacy volumetricCenterSlicing logic.
//
// The cut plane(s) pass through crosshairPos (normalised [0,1]³ texture space) and are oriented by
// crosshairRot (MPR global rotation matrix, volume space → volume space).
//
//   localVec = crosshairRot * (texCoord - crosshairPos)
//   Axes: X = Left/Right,  Y = Up/Down,  Z = Front/Back (view depth)
// *************************************************************************************************
bool isCrosshairCut(vec3 texCoord) {
    if (crosshairCutMode == 0 || !crosshairVisible) return false;

    // Vector from the cut centre to the current sample in the crosshair local frame.
    vec3 v = crosshairRot * (texCoord - crosshairPos);

    // Half-space modes
    if      (crosshairCutMode == 1) return v.x < 0.0;               // RIGHT
    else if (crosshairCutMode == 2) return v.x > 0.0;               // LEFT
    else if (crosshairCutMode == 3) return v.y < 0.0;               // FRONT
    else if (crosshairCutMode == 4) return v.y > 0.0;               // BACK
    else if (crosshairCutMode == 5) return v.z > 0.0;               // UP
    else if (crosshairCutMode == 6) return v.z < 0.0;               // DOWN

    // Quadrant modes — two simultaneous 1/4-space tests along X and Z
    else if (crosshairCutMode == 7) return v.x < 0.0 && v.z > 0.0; // RIGHT_UP
    else if (crosshairCutMode == 8)  return v.x > 0.0 && v.z > 0.0; // LEFT_UP
    else if (crosshairCutMode == 9)  return v.x < 0.0 && v.z < 0.0; // RIGHT_DOWN
    else if (crosshairCutMode == 10)  return v.x > 0.0 && v.z < 0.0; // LEFT_DOWN

    // Octant modes — three simultaneous 1/8-space tests along X, Y, and Z
    else if (crosshairCutMode == 11) return v.x < 0.0 && v.z > 0.0 && v.y < 0.0; // UP_RIGHT_FRONT
    else if (crosshairCutMode == 12) return v.x > 0.0 && v.z > 0.0 && v.y < 0.0; // UP_LEFT_FRONT
    else if (crosshairCutMode == 13) return v.x < 0.0 && v.z < 0.0 && v.y < 0.0; // DOWN_RIGHT_FRONT
    else if (crosshairCutMode == 14) return v.x > 0.0 && v.z < 0.0 && v.y < 0.0; // DOWN_LEFT_FRONT
    else if (crosshairCutMode == 15) return v.x < 0.0 && v.z > 0.0 && v.y > 0.0; // UP_RIGHT_BACK
    else if (crosshairCutMode == 16) return v.x > 0.0 && v.z > 0.0 && v.y > 0.0; // UP_LEFT_BACK
    else if (crosshairCutMode == 17) return v.x < 0.0 && v.z < 0.0 && v.y > 0.0; // DOWN_RIGHT_BACK
    else if (crosshairCutMode == 18) return v.x > 0.0 && v.z < 0.0 && v.y > 0.0; // DOWN_LEFT_BACK

    return false;
}

// Ray entry and exit of the crop box, which is the whole volume [-texelSize, texelSize] when the
// crop is off (cropMin = 0, cropMax = 1).
void intersect(in Ray ray, out float tmin, out float tmax) {
    vec3 boxMin = (2.0 * cropMin - 1.0) * texelSize;
    vec3 boxMax = (2.0 * cropMax - 1.0) * texelSize;
    float tymin, tymax, tzmin, tzmax;
    tmin = (getAabb(boxMin.x, boxMax.x, ray.sign[0]) - ray.origin.x) * ray.invDirection.x;
    tmax = (getAabb(boxMin.x, boxMax.x, 1 - ray.sign[0]) - ray.origin.x) * ray.invDirection.x;
    tymin = (getAabb(boxMin.y, boxMax.y, ray.sign[1]) - ray.origin.y) * ray.invDirection.y;
    tymax = (getAabb(boxMin.y, boxMax.y, 1 - ray.sign[1]) - ray.origin.y) * ray.invDirection.y;
    tzmin = (getAabb(boxMin.z, boxMax.z, ray.sign[2]) - ray.origin.z) * ray.invDirection.z;
    tzmax = (getAabb(boxMin.z, boxMax.z, 1 - ray.sign[2]) - ray.origin.z) * ray.invDirection.z;
    tmin = max(max(tmin, tymin), tzmin);
    tmax = min(min(tmax, tymax), tzmax);
}

vec4 applyTextureColor(float pix){
    return texture(colorMap, vec2(pix, 0.0));
}

// A colour volume already carries the colour that matters — a fusion blend, a Doppler map — so it
// keeps its own RGB and takes only the opacity from the preset LUT, which stays driven by the
// window/level applied to the voxel luminance.
vec4 applyVoxelColor(vec3 texCoord, float pix) {
    vec4 color = applyTextureColor(pix);
    return isColorTexture() ? vec4(getVoxelColor(texCoord), color.a) : color;
}

// Reference thickness (in normalized volume-texture units) over which a segment's slider opacity is
// realized. The per-sample seg opacity is corrected to this reference so the rendered result no longer
// depends on the ray sampling rate; smaller values make the slider ramp up faster.
const float SEG_OPACITY_REF_DIST = 0.1;

// *************************************************************************************************
// Segmentation overlay — samples the segTexture (usampler3D, GL_R32UI) which now stores per-voxel
// storage IDs (one ID per segment, plus extra IDs for overlap combinations) and looks the colour
// up in segColorMap (RGBA8) which is indexed by storage ID. Overlap combinations are pre-blended
// in the LUT by SegmentationVolume.buildSegmentColorLUT(), so no per-bit compositing is needed
// here. Returns vec4(0) when no segment is present or the overlay is disabled.
// *************************************************************************************************

// Converts [0,1]³ volume-texture coordinates to the integer voxel coordinates texelFetch needs.
ivec3 segVoxel(vec3 texCoord) {
    return ivec3(round(texCoord * vec3(textureSize(segTexture, 0)) - vec3(0.5)));
}

// Storage ID at a voxel; 0 (no segment) outside the volume.
uint segIdAt(ivec3 voxel) {
    ivec3 segSize = textureSize(segTexture, 0);
    if (any(lessThan(voxel, ivec3(0))) || any(greaterThanEqual(voxel, segSize))) return 0u;
    return texelFetch(segTexture, voxel, 0).r;
}

vec4 sampleSegOverlay(vec3 texCoord) {
    if (!segOverlayEnabled || segSegmentCount <= 0) return vec4(0.0);

    uint id = segIdAt(segVoxel(texCoord));
    if (id == 0u) return vec4(0.0);

    // Single LUT lookup — combination IDs already point to a pre-composited RGBA.
    return texelFetch(segColorMap, ivec2(int(id), 0), 0);
}

// Smooth surfaces: segOccTexture is the smoothed occupancy of the visible segments (GL_R8,
// trilinear, zero border), above 0.5 inside and below outside. It encodes the in-slice distance to
// the segment boundary, so its 0.5 level runs smoothly from one slice contour to the next where the
// nearest label voxel can only render thick slices as stair steps (see SegOccupancy).
const float SEG_SURFACE_LEVEL = 0.5;

float segSmoothOccupancy(vec3 texCoord) {
    return texture(segOccTexture, texCoord).r;
}

// Segmentation voxel mask — in include mode only the real voxels inside a visible segment are
// composited (the segmentation acts as a stencil, no colour overlay); in exclude mode those
// voxels are removed from the rendering. Visibility follows the colour LUT, so the mask reacts to
// the Segmentation tool checkboxes like the overlay does.
bool isSegMasked(vec3 texCoord) {
    if (segMaskMode == 0) return false;
    bool inside = segSmooth
        ? segSmoothOccupancy(texCoord) >= SEG_SURFACE_LEVEL
        : sampleSegOverlay(texCoord).a > 0.0;
    return segMaskMode == 1 ? !inside : inside;
}

// Radius, in voxels, of the central differences giving the segment surface normal. The differences
// are summed over every radius up to this one rather than taken at a single one: each tap of a
// binary mask contributes only -1, 0 or +1, so one radius yields a handful of directions and the
// surface resolves into four flat shades. Summing three radii gives seventeen. Going wider keeps
// smoothing but starts rounding off features thinner than the radius.
const int SEG_NORMAL_RADIUS = 3;

// Fixed material weights. The anatomy takes its own from lightingMap, which is indexed by voxel
// intensity — meaningless for a label — so segments are lit with constants instead.
//
// The diffuse response spans [SEG_AMBIENT, 1] and therefore only ever *darkens* the palette
// colour. Letting it brighten instead makes the shading invisible: the palette is saturated, so
// any factor above 1 is clipped on the channel that carries the hue, and with a headlight most of
// a surface faces the viewer. The floor keeps an unlit face readable — a segment is an annotation
// before it is a lit surface.
const float SEG_AMBIENT = 0.35;
const float SEG_SPECULAR = 0.30;

// Occupancy of the *visible* segmentation at a voxel. Visibility matters: a hidden segmentation
// wrapped around a visible one would otherwise read as solid on both sides of the surface and
// flatten the gradient to zero, leaving the visible one unshaded.
float segOccupancy(ivec3 voxel) {
    uint id = segIdAt(voxel);
    return id != 0u && texelFetch(segColorMap, ivec2(int(id), 0), 0).a > 0.0 ? 1.0 : 0.0;
}

// Occupancy difference along one axis, summed over every stencil radius.
float segAxisDelta(ivec3 voxel, ivec3 axis) {
    float delta = 0.0;
    for (int r = 1; r <= SEG_NORMAL_RADIUS; ++r) {
        delta += segOccupancy(voxel - axis * r) - segOccupancy(voxel + axis * r);
    }
    return delta;
}

// Un-normalised surface normal of the visible segmentation, pointing outwards like gradient()
// does for the anatomy. Zero-length inside a segment and in empty space, where there is no surface.
vec3 segNormal(ivec3 voxel) {
    return vec3(
        segAxisDelta(voxel, ivec3(1, 0, 0)),
        segAxisDelta(voxel, ivec3(0, 1, 0)),
        segAxisDelta(voxel, ivec3(0, 0, 1)));
}

// Blinn-Phong response of the segment surface, as (diffuse factor, specular addition). The
// diffuse part is a scalar so the segment keeps its hue — colours are what identify a segment, and
// tinting them towards the light would cost more than the depth cue gains. The specular part is
// added as white on top, which is what makes a highlight read as one instead of clipping the hue.
// Returns (1, 0), i.e. unshaded, when no light is enabled.
vec2 segLighting(vec3 texCoord, vec3 N) {
    float diffuseSum = 0.0;
    float specularSum = 0.0;
    bool anyLight = false;
    for (int i = 0; i < 4; ++i) {
        if (lights[i].enabled) {
            anyLight = true;
            vec3 V = normalize(vec3(viewMatrix * lights[i].position) - texCoord);
            vec3 L = normalize(lights[i].position.xyz - texCoord);
            // Double sided, as the anatomy is: the ray may enter a segment from either side.
            vec3 n = dot(L, N) < 0.0 ? -N : N;
            float diffuseCoeff = max(dot(L, n), 0.0);
            vec3 H = normalize(L + V);
            diffuseSum += diffuseCoeff;
            specularSum +=
                diffuseCoeff > 0.0 ? pow(max(dot(H, n), 0.0), lights[i].specularPower) : 0.0;
        }
    }
    if (!anyLight) {
        return vec2(1.0, 0.0);
    }
    return vec2(
        SEG_AMBIENT + (1.0 - SEG_AMBIENT) * min(diffuseSum, 1.0),
        SEG_SPECULAR * min(specularSum, 1.0));
}

// Outward normal of the crosshair cut surface, from a central difference of the kept half-space.
// Derived from isCrosshairCut() itself rather than from the cut mode, so it follows all eighteen
// half-space, quadrant and octant modes — and any later one — without restating them. Zero when
// the sample is not within {delta} of a cut plane.
vec3 crosshairCutNormal(vec3 texCoord, float delta) {
    if (crosshairCutMode == 0 || !crosshairVisible) {
        return vec3(0.0);
    }
    vec3 dx = vec3(delta, 0.0, 0.0);
    vec3 dy = vec3(0.0, delta, 0.0);
    vec3 dz = vec3(0.0, 0.0, delta);
    return vec3(
        (isCrosshairCut(texCoord - dx) ? 0.0 : 1.0) - (isCrosshairCut(texCoord + dx) ? 0.0 : 1.0),
        (isCrosshairCut(texCoord - dy) ? 0.0 : 1.0) - (isCrosshairCut(texCoord + dy) ? 0.0 : 1.0),
        (isCrosshairCut(texCoord - dz) ? 0.0 : 1.0) - (isCrosshairCut(texCoord + dz) ? 0.0 : 1.0));
}

// Crop box face component along one axis: -1 or 1 within {delta} of the lower or upper face, else 0.
// Faces on the volume boundary are left out, the volume ends there as it does without a crop.
float cropFaceAxis(float p, float lo, float hi, float delta) {
    if (lo > 0.0 && p - lo < delta) return -1.0;
    if (hi < 1.0 && hi - p < delta) return 1.0;
    return 0.0;
}

// Outward normal of the crop box faces near the sample, zero away from them. A segment cut open by
// the crop box is lit as the face of the cut rather than left flat.
vec3 cropFaceNormal(vec3 texCoord, float delta) {
    return vec3(
        cropFaceAxis(texCoord.x, cropMin.x, cropMax.x, delta),
        cropFaceAxis(texCoord.y, cropMin.y, cropMax.y, delta),
        cropFaceAxis(texCoord.z, cropMin.z, cropMax.z, delta));
}

// Radius, in voxels, of the central differences of the smoothed occupancy giving the surface normal,
// summed over every radius as in segNormal(). The occupancy varies continuously, so this only has
// to average out the one-voxel steps of each slice contour, which would otherwise shade as streaks.
const int SEG_SMOOTH_NORMAL_RADIUS = 3;

// Un-normalised outward normal of the smoothed surface, as segNormal() is for the label voxels.
vec3 segSmoothNormal(vec3 texCoord) {
    vec3 voxel = 1.0 / vec3(textureSize(segOccTexture, 0));
    vec3 n = vec3(0.0);
    for (int r = 1; r <= SEG_SMOOTH_NORMAL_RADIUS; ++r) {
        vec3 dx = vec3(voxel.x * float(r), 0.0, 0.0);
        vec3 dy = vec3(0.0, voxel.y * float(r), 0.0);
        vec3 dz = vec3(0.0, 0.0, voxel.z * float(r));
        n.x += segSmoothOccupancy(texCoord - dx) - segSmoothOccupancy(texCoord + dx);
        n.y += segSmoothOccupancy(texCoord - dy) - segSmoothOccupancy(texCoord + dy);
        n.z += segSmoothOccupancy(texCoord - dz) - segSmoothOccupancy(texCoord + dz);
    }
    return n;
}

// Position of the 0.5 level between the last sample outside (occupancy occOut) and the first one
// inside (occIn), after two regula falsi steps.
vec3 refineSegSurface(vec3 outside, float occOut, vec3 inside, float occIn) {
    for (int i = 0; i < 2; ++i) {
        float t = clamp((SEG_SURFACE_LEVEL - occOut) / max(occIn - occOut, 1e-4), 0.0, 1.0);
        vec3 p = mix(outside, inside, t);
        float occ = segSmoothOccupancy(p);
        if (occ >= SEG_SURFACE_LEVEL) {
            inside = p;
            occIn = occ;
        } else {
            outside = p;
            occOut = occ;
        }
    }
    return mix(outside, inside, clamp((SEG_SURFACE_LEVEL - occOut) / max(occIn - occOut, 1e-4), 0.0, 1.0));
}

// Colour of the visible segment at a point of the smoothed surface, whose nearest label voxel can
// lie outside: the closest visible one among the eight label voxels around the point.
vec4 segSurfaceColor(vec3 texCoord) {
    vec3 pos = texCoord * vec3(textureSize(segTexture, 0)) - vec3(0.5);
    ivec3 base = ivec3(floor(pos));
    vec4 color = vec4(0.0);
    float best = 4.0;
    for (int i = 0; i < 8; ++i) {
        ivec3 voxel = base + ivec3(i & 1, (i >> 1) & 1, (i >> 2) & 1);
        uint id = segIdAt(voxel);
        if (id != 0u) {
            vec4 c = texelFetch(segColorMap, ivec2(int(id), 0), 0);
            vec3 offset = vec3(voxel) - pos;
            float dist = dot(offset, offset);
            if (c.a > 0.0 && dist < best) {
                best = dist;
                color = c;
            }
        }
    }
    return color;
}

// accumulateSegOverlay() for the smooth surfaces. The inside test is the smoothed occupancy, the
// label texture only gives the colour, and the entry point is refined onto the 0.5 level, where the
// normal is taken. Only the part of that first step lying inside is composited, so a translucent
// segment shows no banding from the sampling either. Returns premultiplied RGBA.
vec4 accumulateSmoothSegOverlay(vec3 start, vec3 stepPos, vec3 ditheredRayStep, int sampleCount) {
    vec4 segAccum = vec4(0.0);
    vec3 rayPos = start;
    vec2 lighting = vec2(1.0, 0.0);
    bool entered = false;
    bool cameThroughCut = false;
    float stepLength = length(stepPos);
    vec4 lastColor = vec4(0.0);
    // Where the ray enters the (crop) box: a segment already inside there was cut open by the box.
    vec3 prevCoord = start + ditheredRayStep;
    float prevOcc = segSmoothOccupancy(prevCoord);

    for (int count = 0; count < sampleCount; count++) {
        rayPos += stepPos;
        vec3 texCoord = rayPos + ditheredRayStep;
        if (isCrosshairCut(texCoord)) {
            cameThroughCut = true;
            prevCoord = texCoord;
            prevOcc = 0.0;
            continue;
        }
        float occ = segSmoothOccupancy(texCoord);
        if (occ < SEG_SURFACE_LEVEL) {
            cameThroughCut = false;
            prevCoord = texCoord;
            prevOcc = occ;
            continue;
        }
        bool crossed = prevOcc < SEG_SURFACE_LEVEL && !cameThroughCut;
        vec3 hit = crossed ? refineSegSurface(prevCoord, prevOcc, texCoord, occ) : texCoord;
        float insideFraction =
            crossed ? clamp(distance(hit, texCoord) / max(stepLength, 1e-6), 0.05, 1.0) : 1.0;
        prevCoord = texCoord;
        prevOcc = occ;

        if (!entered) {
            entered = true;
            if (shading) {
                // As in accumulateSegOverlay(): a ray coming out of the crosshair cut, or starting
                // inside on a crop face, sees that plane and not the segment surface.
                vec3 n = cameThroughCut ? crosshairCutNormal(texCoord, stepLength) : vec3(0.0);
                if (dot(n, n) <= 0.0 && !crossed) {
                    n = cropFaceNormal(texCoord, 2.0 * stepLength);
                }
                if (dot(n, n) <= 0.0) {
                    n = segSmoothNormal(hit);
                }
                if (dot(n, n) > 0.0) {
                    lighting = segLighting(hit, normalize(n));
                }
            }
        }

        vec4 segColor = sampleSegOverlay(texCoord);
        if (segColor.a <= 0.0) {
            segColor = segSurfaceColor(hit);
        }
        if (segColor.a <= 0.0) {
            segColor = lastColor;
        }
        if (segColor.a <= 0.0) {
            continue;
        }
        lastColor = segColor;
        float segA = 1.0 - pow(1.0 - segColor.a, insideFraction / (float(depthSampleNumber) * SEG_OPACITY_REF_DIST));
        float alpha = (1.0 - segAccum.a) * segA;
        segAccum.rgb += alpha * min(segColor.rgb * lighting.x + lighting.y, vec3(1.0));
        segAccum.a += alpha;
        if (segAccum.a >= 0.99) break;
    }
    return segAccum;
}

// *************************************************************************************************
// Accumulates the segmentation overlay along the ray, front to back, shared by the three rendering
// modes. Returns premultiplied RGBA.
//
// Lighting follows the global shading toggle and is evaluated once per ray, at the sample that
// first enters a visible segment — that is where the surface is, and where the normal is defined.
// The resulting scalar then scales every sample behind it, so a translucent segment is shaded as a
// whole rather than only on its first slab, and a ray never pays for more than one normal.
// *************************************************************************************************
vec4 accumulateSegOverlay(vec3 start, vec3 stepPos, vec3 ditheredRayStep, int sampleCount) {
    if (segSmooth) {
        return accumulateSmoothSegOverlay(start, stepPos, ditheredRayStep, sampleCount);
    }
    vec4 segAccum = vec4(0.0);
    vec3 rayPos = start;
    vec2 lighting = vec2(1.0, 0.0);
    bool entered = false;
    bool cameThroughCut = false;
    float stepLength = length(stepPos);

    for (int count = 0; count < sampleCount; count++) {
        rayPos += stepPos;
        vec3 texCoord = rayPos + ditheredRayStep;
        if (isCrosshairCut(texCoord)) {
            cameThroughCut = true;
            continue;
        }
        vec4 segColor = sampleSegOverlay(texCoord);
        if (segColor.a > 0.0) {
            if (!entered) {
                entered = true;
                if (shading) {
                    // A ray stepping straight out of the cut into the segment is looking at the cut
                    // plane, not at the segment's own surface: that face is flush with the segment
                    // interior, where segNormal() is zero, so take the plane's normal instead.
                    vec3 n = cameThroughCut ? crosshairCutNormal(texCoord, stepLength) : vec3(0.0);
                    // Likewise a ray already inside at its first sample entered through a crop face.
                    if (dot(n, n) <= 0.0 && count == 0) {
                        n = cropFaceNormal(texCoord, 2.0 * stepLength);
                    }
                    if (dot(n, n) <= 0.0) {
                        n = segNormal(segVoxel(texCoord));
                    }
                    // Still nothing: the ray began inside the segment and there is no surface to
                    // light. Leaving the colour flat beats lighting it with an arbitrary normal.
                    if (dot(n, n) > 0.0) {
                        lighting = segLighting(texCoord, normalize(n));
                    }
                }
            }
            // Correct the per-voxel opacity for the ray step so the result is independent of the
            // sampling rate (depthSampleNumber) and the opacity slider stays perceptually usable.
            float segA = 1.0 - pow(1.0 - segColor.a, 1.0 / (float(depthSampleNumber) * SEG_OPACITY_REF_DIST));
            float alpha = (1.0 - segAccum.a) * segA;
            segAccum.rgb += alpha * min(segColor.rgb * lighting.x + lighting.y, vec3(1.0));
            segAccum.a += alpha;
            if (segAccum.a >= 0.99) break;
        } else {
            cameThroughCut = false;
        }
    }
    return segAccum;
}

vec4 rayCastingMip(Ray ray, float tmin, float tmax, vec2 uv) {
    vec3 start = (ray.origin.xyz + tmin * ray.direction.xyz + texelSize) / (texelSize + texelSize);
    vec3 end = (ray.origin.xyz + tmax * ray.direction.xyz + texelSize) / (texelSize + texelSize);

    float len = distance(end, start);
    int sampleCount = int(float(depthSampleNumber) * len);

    float mipPix = mipType == mipTypeMin ? 1.0 : 0.0;
    // Colour of the sample the projection retains, so a colour volume keeps its hue through MIP.
    vec3 mipColor = vec3(0.0);
    vec3 texCoord = vec3(0.0);
    float pix = 0.0;

    vec3 rayPos = start;
    float stepSize = 1.0 / sampleCount;
    vec3 stepPos = (end - start) * stepSize;
    vec3 ditheredRayStep = ditherRay ? stepPos * dithering(uv) : stepPos;

    int sumNb = 0;
    // In "segmentation only" mode the anatomy volume raymarch is skipped entirely.
    for (int count = 0; count < sampleCount && !segOnly; count++) {
        rayPos += stepPos;
        texCoord = rayPos + ditheredRayStep;
        if (isCrosshairCut(texCoord)) continue;
        if (isSegMasked(texCoord)) continue;
        pix = getNormalizedWindowLevel(texCoord);

        if (mipType == mipTypeMin) {
            vec4 pixel = applyTextureColor(pix);
            if (pixel.a > 0.01) {
                if (pix < mipPix && isColorTexture()) {
                    mipColor = getVoxelColor(texCoord);
                }
                mipPix = min(mipPix, pix);
                sumNb++;
            }
        } else if (mipType == mipTypeMean) {
            vec4 pixel = applyTextureColor(pix);
            if (pixel.a > 0.01) {
                if (isColorTexture()) {
                    mipColor += getVoxelColor(texCoord);
                }
                mipPix += pix;
                sumNb++;
            }
        } else {
            if (pix > mipPix && isColorTexture()) {
                mipColor = getVoxelColor(texCoord);
            }
            mipPix = max(mipPix, pix);
            if (mipPix >= 0.99) {
                break;
            }
        }
    }

    if (mipType == mipTypeMin && sumNb == 0) {
        mipPix = 0.0;
    } else if (mipType == mipTypeMean) {
        mipPix = sumNb == 0 ? 0.0 : mipPix / float(sumNb);
        mipColor = sumNb == 0 ? vec3(0.0) : mipColor / float(sumNb);
    }
    vec4 pixel = applyTextureColor(mipPix);
    if (isColorTexture()) {
        pixel.rgb = mipColor;
    }
    pixel.a = min(pixel.a * opacityFactor, 1.0);

    // Overlay segmentation colours on top of the MIP result (or render them alone in seg-only
    // mode). Skipped in mask modes: the mask shows real voxels, not segment colours.
    if (segMaskMode == 0 && segOverlayEnabled && (pixel.a > 0.0 || segOnly)) {
        vec4 segAccum = accumulateSegOverlay(start, stepPos, ditheredRayStep, sampleCount);
        if (segOnly) {
            pixel = segAccum;
        } else if (segAccum.a > 0.0) {
            // segAccum.rgb is already premultiplied, so blend it over without re-scaling by its alpha
            pixel.rgb = segAccum.rgb + pixel.rgb * (1.0 - segAccum.a);
            pixel.a = max(pixel.a, segAccum.a);
        }
    }

    return pixel;
}

vec4 rayCastingComposite(Ray ray, float tmin, float tmax, vec2 uv) {
    vec3 start = (ray.origin.xyz + tmin * ray.direction.xyz + texelSize) / (texelSize + texelSize);
    vec3 end = (ray.origin.xyz + tmax * ray.direction.xyz + texelSize) / (texelSize + texelSize);

    float len = distance(end, start);
    int sampleCount = int(float(depthSampleNumber) * len);

    vec4 pxColor = vec4(0.0);
    vec3 texCoord = vec3(0.0);
    float pix = 0.0;

    vec3 rayPos = start;
    float stepSize = 1.0 / sampleCount;
    vec3 stepPos = (end - start) * stepSize;
    vec3 ditheredRayStep = ditherRay ? stepPos * dithering(uv) : stepPos;

    // In "segmentation only" mode the anatomy volume raymarch is skipped entirely.
    for (int count = 0; count < sampleCount && !segOnly; count++) {
        rayPos += stepPos;
        texCoord = rayPos + ditheredRayStep;
        if (isCrosshairCut(texCoord)) continue;
        if (isSegMasked(texCoord)) continue;
        pix = getNormalizedWindowLevel(texCoord);
        //pix = guassianFilter(texCoord, stepSize);
        vec4 pixel = applyVoxelColor(texCoord, pix);
        pixel.a = min(pixel.a * opacityFactor, 1.0);

        if (accurateBlending) {
            // Front-to-back "over": a sample only adds what the samples in front still let through.
            if (pixel.a > 0.0) {
                float a = correctedOpacity(pixel.a, stepPos);
                vec3 color = pixel.rgb;
                if (shading) {
                    vec3 normalPos = smoothShading ? smoothGradient(texCoord) : gradient(texCoord, stepSize);
                    vec3 lit = vec3(0.0);
                    for (int i = 0; i < 4; ++i) {
                        if (lights[i].enabled) {
                            vec3 V = normalize(vec3(viewMatrix * lights[i].position) - texCoord);
                            vec3 L = normalize(lights[i].position.xyz - texCoord);
                            // double sided lighting
                            vec3 n = dot(L, normalPos) < 0.0 ? -normalPos : normalPos;
                            lit += blinnPhong(n, V, L, i, pix, defaultDiffuse);
                        }
                    }
                    color *= lit;
                }
                pxColor.rgb += (1.0 - pxColor.a) * a * color;
                pxColor.a += (1.0 - pxColor.a) * a;
            }
            if (pxColor.a >= 0.99) {
                break;
            }
            continue;
        }

        if (pixel.a > 0.0) {
            float alpha = (1.0 - pixel.a) * pxColor.a;
            if (shading) {
                vec3 normalPos = smoothShading ? smoothGradient(texCoord) : gradient(texCoord, stepSize);
                for (int i = 0; i < 4; ++i) {
                    if (lights[i].enabled) {
                        vec3 V = normalize(vec3(viewMatrix * lights[i].position) - texCoord);
                        vec3 L = normalize(lights[i].position.xyz - texCoord);
                        // double sided lighting
                        if (dot(L, normalPos) < 0.0) {
                            normalPos = -normalPos;
                        }
                        pxColor.rgb = pixel.rgb * blinnPhong(normalPos, V, L, i, pix, defaultDiffuse) * pixel.a + alpha * pxColor.rgb;
                    }
                }
            } else {
                pxColor.rgb = pixel.a * pixel.rgb + alpha * pxColor.rgb;
            }
            pxColor.a = pixel.a + alpha;
        }
        if (pxColor.a >= 0.99) {
            break;
        }
    }

    // Overlay segmentation colours on top of the composited volume
    if (segMaskMode == 0 && segOverlayEnabled && (pxColor.a > 0.0 || segOnly)) {
        vec4 segAccum = accumulateSegOverlay(start, stepPos, ditheredRayStep, sampleCount);
        if (segOnly) {
            pxColor = segAccum;
        } else if (segAccum.a > 0.0) {
            // segAccum.rgb is already premultiplied, so blend it over the volume without re-scaling by its alpha
            pxColor.rgb = segAccum.rgb + pxColor.rgb * (1.0 - segAccum.a);
            pxColor.a = max(pxColor.a, segAccum.a);
        }
    }

    if (accurateBlending && !segOnly && pxColor.a > 0.0 && pxColor.a < 0.99) {
        // The blit weights the colour by its alpha again: hand it the straight colour.
        pxColor.rgb /= pxColor.a;
    }
    if (pxColor.a >= 0.99) {
        pxColor.a = 1.0;
    }
    return pxColor;
}

vec4 rayCastingIsoSurface(Ray ray, float tmin, float tmax, vec2 uv) {
    vec3 start = (ray.origin.xyz + tmin * ray.direction.xyz + texelSize) / (texelSize + texelSize);
    vec3 end = (ray.origin.xyz + tmax * ray.direction.xyz + texelSize) / (texelSize + texelSize);

    float len = distance(end, start);
    int sampleCount = int(float(depthSampleNumber) * len);
    float stepLength = len / float(depthSampleNumber);

    vec3 rayPos = start;
    float stepSize = 1.0 / sampleCount;
    vec3 stepPos = (end - start) * stepSize;
    vec3 ditheredRayStep = ditherRay ? stepPos * dithering(uv) : stepPos;

    vec3 texCoord = vec3(0.0);
    vec4 pxColor = vec4(0.0);
    float pix = 0.0;
    float center = (windowCenter - outputLevelMin) / (outputLevelMax - outputLevelMin);

    bool prev_sign = pix < center;

    // In "segmentation only" mode the anatomy volume raymarch is skipped entirely.
    for (int count = 0; count < sampleCount && !segOnly; count++) {
        rayPos += stepPos;
        texCoord = rayPos + ditheredRayStep;
        if (isCrosshairCut(texCoord)) continue;
        if (isSegMasked(texCoord)) continue;
        pix = getNormalizedWindowLevel(texCoord);
        vec4 pixel = applyVoxelColor(texCoord, pix);
        bool sign_cur = pix > center;
        if (pixel.a > 0.0) {
            if (sign_cur != prev_sign) {
                vec3 normalPos = smoothShading ? smoothGradient(texCoord) : gradient(texCoord, stepSize);
                vec4 diffuse = pixel;

                for (int i = 0; i < 4; ++i) {
                    if (lights[i].enabled) {
                        vec3 V = normalize(vec3(viewMatrix * lights[i].position) - texCoord);
                        vec3 L = normalize(lights[i].position.xyz - texCoord);
                        // double sided lighting
                        if (dot(L, normalPos) < 0.0) {
                            normalPos = -normalPos;
                        }

                        pxColor.rgb = pxColor.rgb + blinnPhong(normalPos, V, L, i, pix, diffuse.rgb);
                    }
                }
                pxColor.a = diffuse.a;
                break;
            }
        }

        if (pxColor.a >= 0.99) {
            break;
        }
    }

    if (pxColor.a >= 0.99) {
        pxColor.a = 1.0;
    }

    // Overlay segmentation colours on top of the iso-surface result
    if (segMaskMode == 0 && segOverlayEnabled && (pxColor.a > 0.0 || segOnly)) {
        vec4 segAccum = accumulateSegOverlay(start, stepPos, ditheredRayStep, sampleCount);
        if (segOnly) {
            pxColor = segAccum;
        } else if (segAccum.a > 0.0) {
            // segAccum.rgb is already premultiplied, so blend it over without re-scaling by its alpha
            pxColor.rgb = segAccum.rgb + pxColor.rgb * (1.0 - segAccum.a);
            pxColor.a = max(pxColor.a, segAccum.a);
        }
    }

    return pxColor;
}

vec4 slice(vec2 uv) {
    float w = 0.5;
    vec3 origin = vec3(uv, w) * texelSize;
    origin = (vec4(origin, 1.0) * viewMatrix).xyz + sliceOffset;
    float pix = getNormalizedWindowLevel(origin);
    if (textureSize(colorMap, 0).x > 2) {
        vec4 pixel = applyVoxelColor(origin, pix);
        pixel.a = min(pixel.a * opacityFactor, 1.0);
        return pixel;
    } else if (isColorTexture()) {
        return vec4(getVoxelColor(origin), 1.0f);
    } else {
        if( applyTextureColor(0.0).r > 0){
            pix = 1.0 - pix;
        }
        return vec4(pix, pix, pix, 1.0f);
    }
}
