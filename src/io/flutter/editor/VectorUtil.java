/*
 * Copyright 2019 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.editor;

public class VectorUtil {
  public static double clamp(double x, double min, double max) {
    return Math.min(Math.max(x, min), max);
  }

  /// 2D dot product.
  public static double dot2(Vector2 x, Vector2 y) { return x.dot(y); }

  /// 3D dot product.
  public static double dot3(Vector3 x, Vector3 y) { return x.dot(y); }

  /// 3D Cross product.
  public static void cross3(Vector3 x, Vector3 y, Vector3 out) {
    x.crossInto(y, out);
  }

  /// 2D cross product. vec2 x vec2.
  public static double cross2(Vector2 x, Vector2 y) { return x.cross(y); }

  /// 2D cross product. double x vec2.
  public static void cross2A(double x, Vector2 y, Vector2 out) {
    final double tempy = x * y.getX();
    out.setX( -x * y.getY());
    out.setY( tempy);
  }

  /// 2D cross product. vec2 x double.
  public static void cross2B(Vector2 x, double y, Vector2 out) {
    final double tempy = -y * x.getX();
    out.setX(y * x.getY());
    out.setY(tempy);
  }

  /*
  /// Sets [u] and [v] to be two vectors orthogonal to each other and
  /// [planeNormal].
  public static void buildPlaneVectors(final Vector3 planeNormal, Vector3 u, Vector3 v) {
    if (planeNormal.z.abs() > math.sqrt1_2) {
      // choose u in y-z plane
      final double a =
        planeNormal.y * planeNormal.y + planeNormal.z * planeNormal.z;
      final double k = 1.0 / math.sqrt(a);
      u
        ..x = 0.0
        ..y = -planeNormal.z * k
        ..z = planeNormal.y * k;

      v
        ..x = a * k
        ..y = -planeNormal[0] * (planeNormal[1] * k)
        ..z = planeNormal[0] * (-planeNormal[2] * k);
    } else {
      // choose u in x-y plane
      final double a =
        planeNormal.x * planeNormal.x + planeNormal.y * planeNormal.y;
      final double k = 1.0 / math.sqrt(a);
      u
        ..x = -planeNormal[1] * k
        ..y = planeNormal[0] * k
        ..z = 0.0;

      v
        ..x = -planeNormal[2] * (planeNormal[0] * k)
        ..y = planeNormal[2] * (-planeNormal[1] * k)
        ..z = a * k;
    }
  }

   */
}

