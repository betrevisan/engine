// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "flutter/shell/platform/embedder/embedder_surface_gl.h"

#include "flutter/shell/common/shell_io_manager.h"

namespace flutter {

EmbedderSurfaceGL::EmbedderSurfaceGL(
    GLDispatchTable gl_dispatch_table,
    bool fbo_reset_after_present,
    std::shared_ptr<EmbedderExternalViewEmbedder> external_view_embedder)
    : gl_dispatch_table_(gl_dispatch_table),
      fbo_reset_after_present_(fbo_reset_after_present),
      external_view_embedder_(external_view_embedder) {
  // Make sure all required members of the dispatch table are checked.
  if (!gl_dispatch_table_.gl_make_current_callback ||
      !gl_dispatch_table_.gl_clear_current_callback ||
      !gl_dispatch_table_.gl_present_callback ||
      !gl_dispatch_table_.gl_fbo_callback) {
    return;
  }

  valid_ = true;
}

EmbedderSurfaceGL::~EmbedderSurfaceGL() = default;

// |EmbedderSurface|
bool EmbedderSurfaceGL::IsValid() const {
  return valid_;
}

// |GPUSurfaceGLDelegate|
std::unique_ptr<GLContextResult> EmbedderSurfaceGL::GLContextMakeCurrent() {
  return std::make_unique<GLContextDefaultResult>(
      gl_dispatch_table_.gl_make_current_callback());
}

// |GPUSurfaceGLDelegate|
bool EmbedderSurfaceGL::GLContextClearCurrent() {
  return gl_dispatch_table_.gl_clear_current_callback();
}

// |GPUSurfaceGLDelegate|
bool EmbedderSurfaceGL::GLContextPresent(const GLPresentInfo& present_info) {
  /// NEW: Pass the damage region (as set by GLContextSetDamageRegion and the
  /// frame damage (in present_info) so that the embedder can keep track of it.
  return gl_dispatch_table_.gl_present_callback(present_info, damage_region_);
}

/// NEW: Adding this function to make it easier to translate a FlutterRect back
/// to a SkIRect.
SkIRect FlutterRectToSkIRect(FlutterRect flutter_rect) {
  SkIRect rect = {static_cast<int32_t>(flutter_rect.left),
                  static_cast<int32_t>(flutter_rect.right),
                  static_cast<int32_t>(flutter_rect.top),
                  static_cast<int32_t>(flutter_rect.bottom)};
  return rect;
}

// |GPUSurfaceGLDelegate|
intptr_t EmbedderSurfaceGL::GLContextFBO(GLFrameInfo frame_info) const {
  /// NEW: updating GLContextFBO to trigger a callback that will not only return
  /// the FBO ID but also its existing damage (at least when doing partial
  /// partial repaint.
  FlutterFrameBuffer fbo = gl_dispatch_table_.gl_fbo_callback(frame_info);
  existing_damage_ = FlutterRectToSkIRect(fbo.damage.damage);
  return fbo.fbo_id;
}

// |GPUSurfaceGLDelegate|
SurfaceFrame::FramebufferInfo EmbedderSurfaceGL::GLContextFramebufferInfo()
    const {
  SurfaceFrame::FramebufferInfo res;
  res.supports_readback = true;
  res.supports_partial_repaint = true;
  res.existing_damage = existing_damage_;
  // TODO(btrevisan): confirm if cliping alignment needs to be updated.
  return res;
}

// |GPUSurfaceGLDelegate|
bool EmbedderSurfaceGL::GLContextFBOResetAfterPresent() const {
  return fbo_reset_after_present_;
}

// |GPUSurfaceGLDelegate|
SkMatrix EmbedderSurfaceGL::GLContextSurfaceTransformation() const {
  auto callback = gl_dispatch_table_.gl_surface_transformation_callback;
  if (!callback) {
    SkMatrix matrix;
    matrix.setIdentity();
    return matrix;
  }
  return callback();
}

// |GPUSurfaceGLDelegate|
EmbedderSurfaceGL::GLProcResolver EmbedderSurfaceGL::GetGLProcResolver() const {
  return gl_dispatch_table_.gl_proc_resolver;
}

// |EmbedderSurface|
std::unique_ptr<Surface> EmbedderSurfaceGL::CreateGPUSurface() {
  const bool render_to_surface = !external_view_embedder_;
  return std::make_unique<GPUSurfaceGLSkia>(
      this,              // GPU surface GL delegate
      render_to_surface  // render to surface
  );
}

// |EmbedderSurface|
sk_sp<GrDirectContext> EmbedderSurfaceGL::CreateResourceContext() const {
  auto callback = gl_dispatch_table_.gl_make_resource_current_callback;
  if (callback && callback()) {
    if (auto context = ShellIOManager::CreateCompatibleResourceLoadingContext(
            GrBackend::kOpenGL_GrBackend, GetGLInterface())) {
      return context;
    } else {
      FML_LOG(ERROR)
          << "Internal error: Resource context available but could not create "
             "a compatible Skia context.";
      return nullptr;
    }
  }

  // The callback was not available or failed.
  FML_LOG(ERROR)
      << "Could not create a resource context for async texture uploads. "
         "Expect degraded performance. Set a valid make_resource_current "
         "callback on FlutterOpenGLRendererConfig.";
  return nullptr;
}

void EmbedderSurfaceGL::GLContextSetDamageRegion(
    const std::optional<SkIRect>& region) {
  /// NEW: Updating the damage_region_ so that it can be passed down to the
  /// embedder using present_with_info.
  /// NOTE: region here refers to the buffer_damage.
  damage_region_ = region;
}

}  // namespace flutter
