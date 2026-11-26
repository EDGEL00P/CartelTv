#version 100
precision mediump float;
uniform sampler2D uTexSampler;
uniform vec2 uTexelSize;
uniform float uRadius;
uniform float uSharpen;
uniform float uDeband;
varying vec2 vTexSamplingCoord;

// White noise breaks up the stepped color bands low-bitrate encodes leave in gradients.
float hash(vec2 p) {
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

void main() {
    vec2 uv = vTexSamplingCoord;
    vec2 o = uTexelSize * uRadius;
    vec3 c = texture2D(uTexSampler, uv).rgb;
    vec3 l = texture2D(uTexSampler, uv - vec2(o.x, 0.0)).rgb;
    vec3 r = texture2D(uTexSampler, uv + vec2(o.x, 0.0)).rgb;
    vec3 t = texture2D(uTexSampler, uv - vec2(0.0, o.y)).rgb;
    vec3 b = texture2D(uTexSampler, uv + vec2(0.0, o.y)).rgb;
    vec3 blur = (l + r + t + b) * 0.25;

    // Deband. Bands hide in shadows, so measure the gradient in gamma space (sqrt RGB),
    // where a shadow band step reads as loud as a bright one. Real edges stay crisp:
    // their gradient clears the smoothstep window and the mix weight collapses to zero.
    float grad = length(sqrt(max(c, vec3(0.0))) - sqrt(max(blur, vec3(0.0))));
    float bandW = uDeband * (1.0 - smoothstep(0.006, 0.03, grad));
    vec3 color = mix(c, blur, bandW);
    color += (hash(uv * 1234.7) - 0.5) * 0.004 * uDeband;

    // Sharpen: unsharp mask against the same blur.
    color += (c - blur) * uSharpen;

    gl_FragColor = vec4(color, 1.0);
}
