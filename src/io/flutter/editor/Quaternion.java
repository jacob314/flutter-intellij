/*
 * Copyright 2019 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.editor;

/// Defines a [Quaternion] (a four-dimensional vector) for efficient rotation
/// calculations.
///
/// Quaternion are better for interpolating between rotations and avoid the
/// [gimbal lock](http://de.wikipedia.org/wiki/Gimbal_Lock) problem compared to
/// euler rotations.
@SuppressWarnings({"Duplicates", "UnnecessaryLocalVariable"})
class Quaternion {
  final double[] _qStorage;

  /// Access the internal [storage] of the quaternions components.
  double[] getStorage() { return _qStorage; }

  /// Access the [x] component of the quaternion.
  double getX() { return _qStorage[0]; }

  void setX(double x) {
    _qStorage[0] = x;
  }

  /// Access the [y] component of the quaternion.
  double getY() { return _qStorage[1]; }
  void setY(double y) {
    _qStorage[1] = y;
  }

  /// Access the [z] component of the quaternion.
  double getZ() { return _qStorage[2]; }
  void setZ(double z) {
    _qStorage[2] = z;
  }

  /// Access the [w] component of the quaternion.
  double getW() { return _qStorage[3]; }
  void setW(double w) {
    _qStorage[3] = w;
  }

  private Quaternion() { _qStorage = new double[4]; }
  public Quaternion(Quaternion other) {
    _qStorage = other._qStorage.clone();
  }

  /// Constructs a quaternion using the raw values [x], [y], [z], and [w].
  public Quaternion(double x, double y, double z, double w) {
    _qStorage = new double[4];
    setValues(x, y, z, w);
  }

/*  /// Constructs a quaternion from a rotation matrix [rotationMatrix].
  static Quaternion fromRotation(Matrix3 rotationMatrix) =>
    new Quaternion._()..setFromRotation(rotationMatrix);

 */
  /// Constructs a quaternion from a rotation of [angle] around [axis].
  static Quaternion axisAngle(Vector3 axis, double angle) {
    final Quaternion ret = new Quaternion();
    ret.setAxisAngle(axis, angle);
    return ret;
  }

  /// Constructs a quaternion to be the rotation that rotates vector [a] to [b].
  static Quaternion fromTwoVectors(Vector3 a, Vector3 b) {
    final Quaternion ret = new Quaternion();
    ret.setFromTwoVectors(a, b);
    return ret;
  }
  /// Constructs a quaternion as a copy of [original].
  static Quaternion copy(Quaternion original) {
    final Quaternion ret = new Quaternion();
    ret.setFrom(original);
    return ret;
  }

  /*
  /// Constructs a quaternion with a random rotation. The random number
  /// generator [rn] is used to generate the random numbers for the rotation.
  static Quaternion random(Math.Random rn) =>
    new Quaternion._()..setRandom(rn);
  */

  /// Constructs a quaternion set to the identity quaternion.
  static Quaternion identity() {
    final Quaternion ret = new Quaternion();
    ret._qStorage[3] = 1.0;
    return ret;
  }

  /// Constructs a quaternion from time derivative of [q] with angular
  /// velocity [omega].
  static Quaternion dq(Quaternion q, Vector3 omega) {
    final Quaternion ret = new Quaternion();
    ret.setDQ(q, omega);
    return ret;
  }

  /// Constructs a quaternion from [yaw], [pitch] and [roll].
  Quaternion euler(double yaw, double pitch, double roll) {
    final Quaternion ret = new Quaternion();
    ret.setEuler(yaw, pitch, roll);
    return ret;
  }

  /// Constructs a quaternion with given double[] as [storage].
  Quaternion(double[] _qStorage) {
    this._qStorage =_qStorage;
  }

  /*
  /// Constructs a quaternion with a [storage] that views given [buffer]
  /// starting at [offset]. [offset] has to be multiple of
  /// [double[].bytesPerElement].
  Quaternion.fromBuffer(ByteBuffer buffer, int offset)
    : _qStorage = new double[].view(buffer, offset, 4);
  */

  /// Returns a new copy of [this].
  @SuppressWarnings("MethodDoesntCallSuperMethod")
  @Override
  public Quaternion clone() {
    return new Quaternion(this);
  }

  /// Copy [source] into [this].
  void setFrom(Quaternion source) {
    final double[] sourceStorage = source._qStorage;
    _qStorage[0] = sourceStorage[0];
    _qStorage[1] = sourceStorage[1];
    _qStorage[2] = sourceStorage[2];
    _qStorage[3] = sourceStorage[3];
  }

  /// Set the quaternion to the raw values [x], [y], [z], and [w].
  void setValues(double x, double y, double z, double w) {
    _qStorage[0] = x;
    _qStorage[1] = y;
    _qStorage[2] = z;
    _qStorage[3] = w;
  }

  /// Set the quaternion with rotation of [radians] around [axis].
  void setAxisAngle(Vector3 axis, double radians) {
    final double len = axis.getLength();
    if (len == 0.0) {
      return;
    }
    final double halfSin = Math.sin(radians * 0.5) / len;
    final double[] axisStorage = axis.getStorage();
    _qStorage[0] = axisStorage[0] * halfSin;
    _qStorage[1] = axisStorage[1] * halfSin;
    _qStorage[2] = axisStorage[2] * halfSin;
    _qStorage[3] = Math.cos(radians * 0.5);
  }

  /// Set the quaternion with rotation from a rotation matrix [rotationMatrix].
  /*
  void setFromRotation(Matrix3 rotationMatrix) {
    final double[] rotationMatrixStorage = rotationMatrix.getStorage();
    final double trace = rotationMatrix.trace();
    if (trace > 0.0) {
      double s = Math.sqrt(trace + 1.0);
      _qStorage[3] = s * 0.5;
      s = 0.5 / s;
      _qStorage[0] = (rotationMatrixStorage[5] - rotationMatrixStorage[7]) * s;
      _qStorage[1] = (rotationMatrixStorage[6] - rotationMatrixStorage[2]) * s;
      _qStorage[2] = (rotationMatrixStorage[1] - rotationMatrixStorage[3]) * s;
    } else {
      final int i = rotationMatrixStorage[0] < rotationMatrixStorage[4]
                    ? (rotationMatrixStorage[4] < rotationMatrixStorage[8] ? 2 : 1)
                    : (rotationMatrixStorage[0] < rotationMatrixStorage[8] ? 2 : 0);
      final int j = (i + 1) % 3;
      final int k = (i + 2) % 3;
      double s = Math.sqrt(rotationMatrixStorage[rotationMatrix.index(i, i)] -
                           rotationMatrixStorage[rotationMatrix.index(j, j)] -
                           rotationMatrixStorage[rotationMatrix.index(k, k)] +
                           1.0);
      _qStorage[i] = s * 0.5;
      s = 0.5 / s;
      _qStorage[3] = (rotationMatrixStorage[rotationMatrix.index(k, j)] -
                      rotationMatrixStorage[rotationMatrix.index(j, k)]) *
                     s;
      _qStorage[j] = (rotationMatrixStorage[rotationMatrix.index(j, i)] +
                      rotationMatrixStorage[rotationMatrix.index(i, j)]) *
                     s;
      _qStorage[k] = (rotationMatrixStorage[rotationMatrix.index(k, i)] +
                      rotationMatrixStorage[rotationMatrix.index(i, k)]) *
                     s;
    }
  }

   */

  void setFromTwoVectors(Vector3 a, Vector3 b) {
    final Vector3 v1 = a.normalized();
    final Vector3 v2 = b.normalized();

    final double c = v1.dot(v2);
    double angle = Math.acos(c);
    Vector3 axis = v1.cross(v2);

    if (Math.abs(1.0 + c) < 0.0005) {
      // c \approx -1 indicates 180 degree rotation
      angle = Math.PI;

      // a and b are parallel in opposite directions. We need any
      // vector as our rotation axis that is perpendicular.
      // Find one by taking the cross product of v1 with an appropriate unit axis
      if (v1.getX() > v1.getY() && v1.getX() > v1.getZ()) {
        // v1 points in a dominantly x direction, so don't cross with that axis
        axis = v1.cross(new Vector3(0.0, 1.0, 0.0));
      } else {
        // Predominantly points in some other direction, so x-axis should be safe
        axis = v1.cross(new Vector3(1.0, 0.0, 0.0));
      }
    } else if (Math.abs(1.0 - c) < 0.0005) {
      // c \approx 1 is 0-degree rotation, axis is arbitrary
      angle = 0.0;
      axis = new Vector3(1.0, 0.0, 0.0);
    }

    setAxisAngle(axis.normalized(), angle);
  }

  /*
  /// Set the quaternion to a random rotation. The random number generator [rn]
  /// is used to generate the random numbers for the rotation.
  void setRandom(Math.Random rn) {
    // From: "Uniform Random Rotations", Ken Shoemake, Graphics Gems III,
    // pg. 124-132.
    final double x0 = rn.nextDouble();
    final double r1 = Math.sqrt(1.0 - x0);
    final double r2 = Math.sqrt(x0);
    final double t1 = Math.pi * 2.0 * rn.nextDouble();
    final double t2 = Math.pi * 2.0 * rn.nextDouble();
    final double c1 = Math.cos(t1);
    final double s1 = Math.sin(t1);
    final double c2 = Math.cos(t2);
    final double s2 = Math.sin(t2);
    _qStorage[0] = s1 * r1;
    _qStorage[1] = c1 * r1;
    _qStorage[2] = s2 * r2;
    _qStorage[3] = c2 * r2;
  }
*/
  /// Set the quaternion to the time derivative of [q] with angular velocity
  /// [omega].
  void setDQ(Quaternion q, Vector3 omega) {
    final double[] qStorage = q._qStorage;
    final double[] omegaStorage = omega.getStorage();
    final double qx = qStorage[0];
    final double qy = qStorage[1];
    final double qz = qStorage[2];
    final double qw = qStorage[3];
    final double ox = omegaStorage[0];
    final double oy = omegaStorage[1];
    final double oz = omegaStorage[2];
    final double _x = ox * qw + oy * qz - oz * qy;
    final double _y = oy * qw + oz * qx - ox * qz;
    final double _z = oz * qw + ox * qy - oy * qx;
    final double _w = -ox * qx - oy * qy - oz * qz;
    _qStorage[0] = _x * 0.5;
    _qStorage[1] = _y * 0.5;
    _qStorage[2] = _z * 0.5;
    _qStorage[3] = _w * 0.5;
  }

  /// Set quaternion with rotation of [yaw], [pitch] and [roll].
  void setEuler(double yaw, double pitch, double roll) {
    final double halfYaw = yaw * 0.5;
    final double halfPitch = pitch * 0.5;
    final double halfRoll = roll * 0.5;
    final double cosYaw = Math.cos(halfYaw);
    final double sinYaw = Math.sin(halfYaw);
    final double cosPitch = Math.cos(halfPitch);
    final double sinPitch = Math.sin(halfPitch);
    final double cosRoll = Math.cos(halfRoll);
    final double sinRoll = Math.sin(halfRoll);
    _qStorage[0] = cosRoll * sinPitch * cosYaw + sinRoll * cosPitch * sinYaw;
    _qStorage[1] = cosRoll * cosPitch * sinYaw - sinRoll * sinPitch * cosYaw;
    _qStorage[2] = sinRoll * cosPitch * cosYaw - cosRoll * sinPitch * sinYaw;
    _qStorage[3] = cosRoll * cosPitch * cosYaw + sinRoll * sinPitch * sinYaw;
  }

  /// Normalize [this].
  public double normalize() {
    final double l = getLength();
    if (l == 0.0) {
      return 0.0;
    }
    final double d = 1.0 / l;
    _qStorage[0] *= d;
    _qStorage[1] *= d;
    _qStorage[2] *= d;
    _qStorage[3] *= d;
    return l;
  }

  /// Conjugate [this].
  public void conjugate() {
    _qStorage[2] = -_qStorage[2];
    _qStorage[1] = -_qStorage[1];
    _qStorage[0] = -_qStorage[0];
  }

  /// Invert [this].
  public void inverse() {
    final double l = 1.0 / getLength2();
    _qStorage[3] = _qStorage[3] * l;
    _qStorage[2] = -_qStorage[2] * l;
    _qStorage[1] = -_qStorage[1] * l;
    _qStorage[0] = -_qStorage[0] * l;
  }

  /// Normalized copy of [this].
  public Quaternion normalized() {
    final Quaternion ret = clone();
    ret.normalize();
    return ret;
  }

  /// Conjugated copy of [this].
  public Quaternion conjugated() {
    final Quaternion ret = clone();
    ret.conjugate();
    return ret;
  }

  /// Inverted copy of [this].
  public Quaternion inverted() {
    final Quaternion ret = clone();
    ret.inverse();
    return ret;
  }

  /// [radians] of rotation around the [axis] of the rotation.
  public double getRadians() { return 2.0 * Math.acos(_qStorage[3]); }

  /// [axis] of rotation.
  public Vector3 getAxis() {
    final double den = 1.0 - (_qStorage[3] * _qStorage[3]);
    if (den < 0.0005) {
      // 0-angle rotation, so axis does not matter
      return new Vector3();
    }

    final double scale = 1.0 / Math.sqrt(den);
    return new Vector3(
      _qStorage[0] * scale, _qStorage[1] * scale, _qStorage[2] * scale);
  }

  /// Length squared.
  public double getLength2() {
    final double x = _qStorage[0];
    final double y = _qStorage[1];
    final double z = _qStorage[2];
    final double w = _qStorage[3];
    return (x * x) + (y * y) + (z * z) + (w * w);
  }

  /// Length.
  public double getLength() { return Math.sqrt(getLength2()); }

  /// Returns a copy of [v] rotated by quaternion.
  public Vector3 rotated(Vector3 v) {
    final Vector3 out = v.clone();
    rotate(out);
    return out;
  }

  /// Rotates [v] by [this].
  public Vector3 rotate(Vector3 v) {
    // conjugate(this) * [v,0] * this
    final double _w = _qStorage[3];
    final double _z = _qStorage[2];
    final double _y = _qStorage[1];
    final double _x = _qStorage[0];
    final double tiw = _w;
    final double tiz = -_z;
    final double tiy = -_y;
    final double tix = -_x;
    final double tx = tiw * v.getX() + tix * 0.0 + tiy * v.getZ() - tiz * v.getY();
    final double ty = tiw * v.getY() + tiy * 0.0 + tiz * v.getX() - tix * v.getZ();
    final double tz = tiw * v.getZ() + tiz * 0.0 + tix * v.getY() - tiy * v.getX();
    final double tw = tiw * 0.0 - tix * v.getX() - tiy * v.getY() - tiz * v.getZ();
    final double result_x = tw * _x + tx * _w + ty * _z - tz * _y;
    final double result_y = tw * _y + ty * _w + tz * _x - tx * _z;
    final double result_z = tw * _z + tz * _w + tx * _y - ty * _x;
    final double[] vStorage = v.getStorage();
    vStorage[2] = result_z;
    vStorage[1] = result_y;
    vStorage[0] = result_x;
    return v;
  }

  /// Add [arg] to [this].
  public void add(Quaternion arg) {
    final double[] argStorage = arg._qStorage;
    _qStorage[0] = _qStorage[0] + argStorage[0];
    _qStorage[1] = _qStorage[1] + argStorage[1];
    _qStorage[2] = _qStorage[2] + argStorage[2];
    _qStorage[3] = _qStorage[3] + argStorage[3];
  }

  /// Subtracts [arg] from [this].
  public void sub(Quaternion arg) {
    final double[] argStorage = arg._qStorage;
    _qStorage[0] = _qStorage[0] - argStorage[0];
    _qStorage[1] = _qStorage[1] - argStorage[1];
    _qStorage[2] = _qStorage[2] - argStorage[2];
    _qStorage[3] = _qStorage[3] - argStorage[3];
  }

  /// Scales [this] by [scale].
  public void scale(double scale) {
    _qStorage[3] = _qStorage[3] * scale;
    _qStorage[2] = _qStorage[2] * scale;
    _qStorage[1] = _qStorage[1] * scale;
    _qStorage[0] = _qStorage[0] * scale;
  }

  /// Scaled copy of [this].
  public Quaternion scaled(double scale) {
    final Quaternion ret = clone();
    ret.scale(scale);
    return ret;
  }

  /// [this] rotated by [other].
  public Quaternion operatorMultiply(Quaternion other) {
    final double _w = _qStorage[3];
    final double _z = _qStorage[2];
    final double _y = _qStorage[1];
    final double _x = _qStorage[0];
    final double[] otherStorage = other._qStorage;
    final double ow = otherStorage[3];
    final double oz = otherStorage[2];
    final double oy = otherStorage[1];
    final double ox = otherStorage[0];
    return new Quaternion(
      _w * ox + _x * ow + _y * oz - _z * oy,
      _w * oy + _y * ow + _z * ox - _x * oz,
      _w * oz + _z * ow + _x * oy - _y * ox,
      _w * ow - _x * ox - _y * oy - _z * oz);
  }

  /// Returns copy of [this] + [other].
  public Quaternion operatorAdd(Quaternion other) {
    final Quaternion ret = clone();
    ret.add(other);
    return ret;
  }

  /// Returns copy of [this] - [other].
  public Quaternion operatorSub(Quaternion other) {
    final Quaternion ret = clone();
    ret.sub(other);
    return ret;
  }

  /// Returns negated copy of [this].
  public Quaternion operatorConjugated() {
    final Quaternion ret = clone();
    ret.conjugated();
    return ret;
  }

  /*
  /// Access the component of the quaternion at the index [i].
  double operator [](int i) => _qStorage[i];

  /// Set the component of the quaternion at the index [i].
  void operator []=(int i, double arg) {
    _qStorage[i] = arg;
  }
  */

  /// Returns a rotation matrix containing the same rotation as [this].
/*
  Matrix3 asRotationMatrix() => copyRotationInto(new Matrix3.zero());

  /// Set [rotationMatrix] to a rotation matrix containing the same rotation as
  /// [this].
  Matrix3 copyRotationInto(Matrix3 rotationMatrix) {
    final double d = length2;
    assert(d != 0.0);
    final double s = 2.0 / d;

    final double _x = _qStorage[0];
    final double _y = _qStorage[1];
    final double _z = _qStorage[2];
    final double _w = _qStorage[3];

    final double xs = _x * s;
    final double ys = _y * s;
    final double zs = _z * s;

    final double wx = _w * xs;
    final double wy = _w * ys;
    final double wz = _w * zs;

    final double xx = _x * xs;
    final double xy = _x * ys;
    final double xz = _x * zs;

    final double yy = _y * ys;
    final double yz = _y * zs;
    final double zz = _z * zs;

    final double[] rotationMatrixStorage = rotationMatrix.getStorage();
    rotationMatrixStorage[0] = 1.0 - (yy + zz); // column 0
    rotationMatrixStorage[1] = xy + wz;
    rotationMatrixStorage[2] = xz - wy;
    rotationMatrixStorage[3] = xy - wz; // column 1
    rotationMatrixStorage[4] = 1.0 - (xx + zz);
    rotationMatrixStorage[5] = yz + wx;
    rotationMatrixStorage[6] = xz + wy; // column 2
    rotationMatrixStorage[7] = yz - wx;
    rotationMatrixStorage[8] = 1.0 - (xx + yy);
    return rotationMatrix;
  }

 */

  /// Printable string.
  @Override()
  public String toString() {
    return "" + _qStorage[0] + ", " + _qStorage[1] + ", " + _qStorage[2] + " @ " + _qStorage[3];
  }

  /// Relative error between [this] and [correct].
  public double relativeError(Quaternion correct) {
    final Quaternion diff = correct.operatorSub(this);
    final double norm_diff = diff.getLength();
    final double correct_norm = correct.getLength();
    return norm_diff / correct_norm;
  }

  /// Absolute error between [this] and [correct].
  public double absoluteError(Quaternion correct) {
    final double this_norm = getLength();
    final double correct_norm = correct.getLength();
    final double norm_diff = Math.abs(this_norm - correct_norm);
    return norm_diff;
  }
}
