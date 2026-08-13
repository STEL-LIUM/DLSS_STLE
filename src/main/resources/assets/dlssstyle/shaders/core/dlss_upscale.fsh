#version 330 core

// DLSS-style resolve: reconstruct the display-resolution image from a
// jittered lower-resolution render plus the accumulated history.
//
// 330 core (not vanilla's 150) for two reasons: explicit fragment output
// locations, so this single pass writes the SCREEN and the HISTORY buffer
// at once (copying the frame into history afterwards was a second
// full-resolution operation every frame); and texelFetch-based kernels.
//
// THE THING THIS SHADER GETS RIGHT THAT THE PREVIOUS ONE DID NOT:
// the sub-pixel jitter belongs in the FILTER WEIGHTS, not in the sample
// coordinates. The old code did `texture(Sampler0, uv - Jitter)`, which is
// a bilinear resample at a fractional offset - a low-pass filter that
// averages away exactly the sub-pixel detail the jitter had just created,
// by a different amount in a different direction every frame. Jitter could
// therefore only ever make the image blurrier. Here every tap is a POINT
// sample on the source grid and the jitter shifts each tap's DISTANCE to
// the output pixel centre, which is what turns successive jittered frames
// into real reconstructed detail.

uniform sampler2D Sampler0;   // current low-resolution frame (jittered)
uniform sampler2D Sampler1;   // previous resolved frame, display resolution
uniform sampler2D Sampler2;   // WORLD depth, snapshotted before the hand clear
uniform sampler2D Sampler3;   // live depth: 1.0 except the first-person hand
uniform vec2 InSize;          // source size in texels
uniform float Sharpness;
uniform float HistoryBlend;
// Sub-pixel camera jitter for this frame, in source-UV units.
uniform vec2 Jitter;
// min(1.99, 1/scale): keeps the kernel footprint a constant ~2 OUTPUT
// pixels at every preset. Without it Performance mode goes to mush.
uniform float KernelBias;
// prevViewProj * inverse(currViewProj): carries an NDC+depth point from
// this frame's camera to last frame's screen.
uniform mat4 ReprojMat;
// Rotation-only twin for far-plane pixels. The sky writes no depth, so it
// sits at 1.0; the translation term would give it infinite parallax.
uniform mat4 ReprojMatSky;
// 0 while no trustworthy history exists (fresh or resized buffer, preset
// change). Vanilla clears render targets to WHITE, so blending against a
// virgin history washes the world out for several frames.
uniform float HistoryValid;

in vec2 texCoord;
// 0 = the screen. 1 = the history buffer, attached only on temporal frames;
// writing an unattached output is legal and discarded.
layout(location = 0) out vec4 fragColor;
layout(location = 1) out vec4 historyOut;

// Reinhard tone map for the accumulation space. Blending and clamping in
// tonemapped space is Karis' standard anti-firefly measure: one very bright
// sample can otherwise dominate the average and flicker.
vec3 tonemap(vec3 c) {
    return c / (1.0 + max(max(c.r, c.g), c.b));
}

vec3 untonemap(vec3 c) {
    return c / max(1.0e-4, 1.0 - max(max(c.r, c.g), c.b));
}

// YCoCg for the neighbourhood clamp. Luma and chroma separate cleanly, so
// the clamp box hugs the real colour distribution instead of the loose
// axis-aligned RGB cube - fewer false rejections, so more history survives.
vec3 rgb2ycocg(vec3 c) {
    return vec3(0.25 * c.r + 0.5 * c.g + 0.25 * c.b,
                0.5 * c.r - 0.5 * c.b,
                -0.25 * c.r + 0.5 * c.g - 0.25 * c.b);
}

vec3 ycocg2rgb(vec3 c) {
    return vec3(c.x + c.y - c.z, c.x + c.z, c.x - c.y - c.z);
}

// Lanczos-2, squared-distance form from FSR1's ffx_fsr1.h (MIT) - no sin,
// no sqrt. Lanczos has zeros at integer offsets, so at zero jitter it is an
// IDENTITY filter. That property is why it must be Lanczos and not a
// Gaussian here: DLAA is the default preset and runs at scale 1.0, where a
// Gaussian colour kernel would soften the mod's default configuration.
float lanczos2ApproxSq(float d2) {
    d2 = min(d2, 4.0);
    float wB = (2.0 / 5.0) * d2 - 1.0;
    float wA = (1.0 / 4.0) * d2 - 1.0;
    wB *= wB;
    wA *= wA;
    wB = (25.0 / 16.0) * wB - (25.0 / 16.0 - 1.0);
    return wB * wA;
}

void main() {
    vec2 jitterPx = Jitter * InSize;
    vec2 pOut = texCoord * InSize;              // output centre, clean space
    ivec2 base = ivec2(floor(pOut + jitterPx)); // where it landed in the jittered image
    ivec2 maxTexel = ivec2(InSize) - 1;

    vec2 accY = vec2(0.0);                      // Karis-weighted luma*w, w (chroma is half-rate, below)
    float wSum = 0.0;                           // raw Lanczos weight sum, for confidence
    // ONE fetch pass, two ALU loops: each tap's luma and Lanczos weight are
    // held in registers so the firefly clamp below can use the FULL 3x3
    // luma statistics before anything accumulates. Indexing is the constant
    // (y+1)*3+(x+1), so the unrolled loop never spills to scratch memory.
    float tapY[9];
    float tapW[9];
    vec3 m1 = vec3(0.0);
    vec3 m2 = vec3(0.0);
    float bw = 0.0;
    vec3 boxMin = vec3(1.0e9);
    vec3 boxMax = vec3(-1.0e9);

    for (int y = -1; y <= 1; ++y) {
        for (int x = -1; x <= 1; ++x) {
            ivec2 sp = clamp(base + ivec2(x, y), ivec2(0), maxTexel);
            vec3 c = rgb2ycocg(tonemap(texelFetch(Sampler0, sp, 0).rgb));

            // THE SIGN, and it is the whole thing: this texel's centre sits
            // at (sp + 0.5) in the JITTERED image, so its position in clean
            // output space is (sp + 0.5) - jitterPx. Inverting this yields an
            // image that looks almost right while accumulating double-
            // amplitude jitter - the trap the previous shader fell into.
            vec2 d = (vec2(sp) + 0.5 - jitterPx) - pOut;

            vec2 db = d * KernelBias;
            float w = lanczos2ApproxSq(dot(db, db));
            w *= float(abs(w) > 1.0e-3);        // negative-lobe guard
            tapY[(y + 1) * 3 + (x + 1)] = c.x;
            tapW[(y + 1) * 3 + (x + 1)] = w;
            wSum += w;

            float bwi = exp(-2.0 * dot(d, d));  // rectification weight
            m1 += c * bwi;
            m2 += c * c * bwi;
            bw += bwi;
            boxMin = min(boxMin, c);
            boxMax = max(boxMax, c);
        }
    }

    // FIREFLY CLAMP, then the Karis-weighted accumulation - now that the
    // 3x3 statistics exist. Each tap's luma is clamped to mean + 2 sigma
    // (+0.02 absolute headroom), so a single sparkle pixel (pack SSR,
    // animated water normals) physically cannot dominate the kernel,
    // whatever its brightness. The clamp feeds ONLY the accumulator: the
    // clip box and the sharpen mean still see the true distribution.
    // 1.2.1: SPATIAL-PATH-ONLY (HistoryBlend == 0, i.e. shader packs).
    // The temporal path already bounds outliers three ways - Reinhard
    // accumulation space, the deringing box on `current`, and the
    // variance-clipped 0.75..0.97 history blend that averages any
    // one-frame outlier 4:1 or better - and the field-verified DLAA
    // shadow fixes (sigma floor, Karis weights, blend boost) predate this
    // clamp and do not depend on it. The branch is on a uniform, so the
    // whole draw takes one predicated path; min(y, 1e9) is an identity
    // when temporal.
    float yClampMax = 1.0e9;
    if (HistoryBlend <= 0.0) {
        float meanY = m1.x / max(bw, 1.0e-4);
        float sigmaY = sqrt(max(m2.x / max(bw, 1.0e-4) - meanY * meanY, 0.0));
        // 2.0 sigma, and it stays there. Tightening to 1.5 was field-tested
        // and made sunlit glass WORSE: the clamp then bites every frame and
        // its bite depth moves with the neighbourhood stats, so the
        // threshold itself becomes a flickering brightness modulator.
        // 2-sigma is the proven edge: stable bright pixels pass strictly;
        // only true outliers are pulled in.
        yClampMax = meanY + 2.0 * sigmaY + 0.02;
    }
    // Karis anti-flicker weighting: damp each tap by 1/(1+k*Y) so a bright
    // outlier cannot seesaw the average against its dark neighbours as the
    // jitter phase moves it in and out of the kernel - the classic TAA
    // shadow-edge flicker. The division by the identically-weighted sum
    // inverts the weighting, so flat regions come through untouched.
    // Spatial-only frames (HistoryBlend == 0: shader packs) get a 3x
    // harder curve: with no history to average sparkle over TIME, it has
    // to be tamed in SPACE. The temporal path keeps the shipped weighting.
    float karisScale = (HistoryBlend > 0.0) ? 1.0 : 3.0;
    for (int i = 0; i < 9; ++i) {
        float yTap = min(tapY[i], yClampMax);
        float karis = 1.0 / (1.0 + karisScale * yTap);
        accY += vec2(yTap * tapW[i] * karis, tapW[i] * karis);
    }

    // HALF-RATE CHROMA. Only LUMA earns the jitter-aware Lanczos
    // reconstruction: the eye's chroma acuity is a fraction of its luma
    // acuity (the same asymmetry 4:2:0 video is built on), so Co/Cg take
    // one bilinear tap at this pixel's position in the jittered image.
    // Yes, that is a coordinate-offset resample - the exact filter the
    // header forbids for the image as a whole - applied deliberately where
    // its low-pass cost is invisible. Chroma still passes through the
    // neighbourhood clamp and the variance clip, so it cannot ring or
    // ghost past what the 3x3 stats endorse.
    vec3 chroma = rgb2ycocg(tonemap(texture(Sampler0, texCoord + Jitter).rgb));
    // CHROMA FIREFLY CLAMP (stained glass under packs): the deringing box
    // below cannot catch a TINTED sparkle - the sparkle texel is IN the
    // 3x3, so the box is built around it, and the bilinear chroma tap is
    // a convex combination of in-window texels that could never leave the
    // box anyway. Bound the tap against the neighbourhood's chroma
    // STATISTICS instead, the exact analog of the luma clamp above:
    // mean +/- 2 sigma (+0.01 headroom). A stable chromatic edge raises
    // its own local mean and sigma enough to pass (same maths as luma);
    // a lone SSR sparkle seen through red glass does not.
    // 1.2.1: spatial-path-only, same argument as the luma clamp above.
    if (HistoryBlend <= 0.0) {
        vec2 meanC = m1.yz / max(bw, 1.0e-4);
        vec2 sigmaC = sqrt(max(m2.yz / max(bw, 1.0e-4) - meanC * meanC, vec2(0.0)));
        chroma.yz = clamp(chroma.yz,
                          meanC - 2.0 * sigmaC - 0.01,
                          meanC + 2.0 * sigmaC + 0.01);
    }
    vec3 current = vec3(accY.x / max(accY.y, 1.0e-4), chroma.yz);
    current = clamp(current, boxMin, boxMax);   // deringing
    // Confidence stays the RAW kernel coverage: the Karis-weighted sum would
    // read bright pixels as low coverage and over-blend history there.
    float sampleConfidence = clamp(wSum, 0.0, 1.0);

    vec3 resolved = current;

    if (HistoryValid > 0.5) {
        vec2 depthUV = clamp(texCoord, vec2(0.0), vec2(1.0));
        float depth = texture(Sampler2, depthUV).r;

        mat4 reproj = (depth >= 0.99999) ? ReprojMatSky : ReprojMat;
        vec4 prev = reproj * vec4(texCoord * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
        vec2 prevUV = (prev.xy / prev.w) * 0.5 + 0.5;

        // The first-person hand and screen overlays are view-locked: they do
        // not move with the world, so world reprojection is wrong for them.
        // After vanilla's depth clear the live buffer is 1.0 everywhere
        // except exactly those pixels - an exact mask, free, nothing to tune.
        if (texture(Sampler3, depthUV).r < 0.9999) {
            prevUV = texCoord;
        }

        bool offscreen = any(lessThan(prevUV, vec2(0.0)))
                      || any(greaterThan(prevUV, vec2(1.0)));

        if (!offscreen) {
            vec3 history = rgb2ycocg(tonemap(texture(Sampler1, prevUV).rgb));

            // Variance clipping: build the box from the neighbourhood's mean
            // and standard deviation rather than its min/max. A min/max box
            // is set by outliers and is far too loose in flat areas and too
            // tight on edges; the statistical box tracks the actual colour
            // distribution, which is what lets a CORRECT history survive
            // instead of being clipped away every frame.
            vec3 mean = m1 / max(bw, 1.0e-4);
            vec3 var = max(vec3(0.0), m2 / max(bw, 1.0e-4) - mean * mean);
            // The 1.25-sigma box collapses in shadow: dark neighbourhoods
            // have near-zero variance, so a CORRECT history lands outside a
            // box a fraction of a percent wide and is clipped every frame --
            // which the eye reads as shadows flickering with the jitter. An
            // absolute floor widens dark boxes proportionally far more than
            // bright ones, so the fix targets shadow almost exclusively.
            // Y gets twice the chroma floor: flicker is luminance-first.
            // The floor is LUMA-ADAPTIVE: a constant 0.006 was still too
            // tight where the jittered 3x3 window slides across a hard
            // shadow edge and the box CENTRE moves every frame, so shadows
            // (mean.x near 0) get a ~2.3x wider box while midtones and
            // highlights keep a tight one and stay crisp.
            vec3 sigmaFloor = mix(vec3(0.014, 0.007, 0.007),
                                  vec3(0.004, 0.002, 0.002),
                                  smoothstep(0.0, 0.25, mean.x));
            // Bright mirror: animated specular (sun glint on water, pack
            // SSR sparkle) is LEGITIMATE frame-to-frame variation; widen
            // slightly at high luma so it converges instead of popping
            // against the clip box every frame.
            sigmaFloor += vec3(0.006, 0.003, 0.003) * smoothstep(0.7, 1.0, mean.x);
            vec3 sigma = sqrt(var) * 1.25 + sigmaFloor;
            vec3 lo = max(mean - sigma, boxMin);
            vec3 hi = min(mean + sigma, boxMax);

            // Clip TOWARDS the centre along the ray rather than clamping per
            // channel: clamping shifts hue when it bites, clipping does not.
            vec3 toHistory = history - mean;
            vec3 units = abs(toHistory) / max(abs(vec3(0.5) * (hi - lo)), vec3(1.0e-4));
            float maxUnit = max(max(units.x, units.y), units.z);
            if (maxUnit > 1.0) {
                history = mean + toHistory / maxUnit;
            }

            float blend = HistoryBlend * sampleConfidence;
            // Dark-region blend boost: accumulate harder exactly where the
            // flicker lives. Shadows hold ~6% more history per frame; the
            // 0.97 cap still bounds the memory, so nothing ghosts forever.
            blend *= mix(1.06, 1.0, smoothstep(0.0, 0.2, mean.x));
            resolved = mix(current, history, clamp(blend, 0.0, 0.97));
        }
    }

    vec3 outRgb = untonemap(ycocg2rgb(resolved));

    // Sharpen last, on the reconstructed image, scaled entirely by
    // Sharpness so the control means what it says.
    vec3 meanRgb = untonemap(ycocg2rgb(m1 / max(bw, 1.0e-4)));
    outRgb += (outRgb - meanRgb) * Sharpness;
    outRgb = max(outRgb, vec3(0.0));

    fragColor = vec4(outRgb, 1.0);
    historyOut = vec4(outRgb, 1.0);
}
