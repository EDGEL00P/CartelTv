#version 100
uniform mat4 uTransformationMatrix;
uniform mat4 uTexTransformationMatrix;
attribute vec4 aFramePosition;
varying vec2 vTexSamplingCoord;
void main() {
    gl_Position = uTransformationMatrix * aFramePosition;
    // aFramePosition spans -1..1; with an identity texture matrix, fold that into 0..1 UV space.
    vTexSamplingCoord = (uTexTransformationMatrix * aFramePosition).xy * 0.5 + 0.5;
}
