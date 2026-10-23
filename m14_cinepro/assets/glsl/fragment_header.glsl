#version 320 es

/*
 * Created by Daniel Hernandez Portugues <danielh@blackmagicdesign.com> on 2024/8/13.
 * Verbatim ground-truth port (docs/stage4_shaders.md section 1.1) for M14 CinePro.
 */

#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
uniform highp samplerExternalOES cameraTexture;
in vec2 cameraTexCoordinates;
out vec4 fragColor;

// Uniforms to activate/deactivate all the effects but keeping individual states
uniform int areEffectsVisible;
