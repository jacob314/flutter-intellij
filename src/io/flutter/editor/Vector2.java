/*
 * Copyright 2019 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.editor;


import java.util.Arrays;

public class Vector2 implements Vector {
  final double[] _v2storage;

  /// The components of the vector.
  @Override()
  public double[] getStorage() { return _v2storage; }

  /// Set the values of [result] to the minimum of [a] and [b] for each line.
  static void min(Vector2 a, Vector2 b, Vector2 result) {
    result.setX(Math.min(a.getX(), b.getX()));
    result.setY(Math.min(a.getY(), b.getY()));
  }

  /// Set the values of [result] to the maximum of [a] and [b] for each line.
  static void max(Vector2 a, Vector2 b, Vector2 result) {
    result
      .setX(Math.max(a.getX(), b.getX()));
    result
      .setY(Math.max(a.getY(), b.getY()));
  }

  /// Interpolate between [min] and [max] with the amount of [a] using a linear
  /// interpolation and store the values in [result].
  static void mix(Vector2 min, Vector2 max, double a, Vector2 result) {
    result.setX(min.getX() + a * (max.getX() - min.getX()));
    result.setY(min.getY() + a * (max.getY()) - min.getY());
  }

  /// Construct a new vector with the specified values.
  Vector2(double x, double y) {
    _v2storage = new double[] {x,y};
  }
  /*
  /// Initialized with values from [array] starting at [offset].
  static Vector2.array(List<double> array, [int offset = 0]) =>
    new Vector2.zero()..copyFromArray(array, offset);
*/
  /// Zero vector.
  public Vector2() {
    _v2storage = new double[2];
  }
  /// Splat [value] into all lanes of the vector.
  static Vector2 all(double value) {
    final Vector2 ret = new Vector2();
    ret.splat(value);
    return ret;
  }

  /// Copy of [other].
  static Vector2 copy(Vector2 other) {
    final Vector2 ret = new Vector2();
    ret.setFrom(other);
    return ret;
  }

  /*
  /// Constructs Vector2 with a given [double[]] as [storage].
  Vector2.fromdouble[](this._v2storage);

  /// Constructs Vector2 with a [storage] that views given [buffer] starting at
  /// [offset]. [offset] has to be multiple of [double[].bytesPerElement].
  Vector2.fromBuffer(ByteBuffer buffer, int offset)
    : _v2storage = new double[].view(buffer, offset, 2);


  /// Generate random vector in the range (0, 0) to (1, 1). You can
  /// optionally pass your own random number generator.
  factory Vector2.random([Math.Random rng]) {
    rng ??= new Math.Random();
    return new Vector2(rng.nextDouble(), rng.nextDouble());
  }
*/
  /// Set the values of the vector.
  public void setValues(double x_, double y_) {
    _v2storage[0] = x_;
    _v2storage[1] = y_;
  }

  /// Zero the vector.
  public void setZero() {
    _v2storage[0] = 0.0;
    _v2storage[1] = 0.0;
  }

  /// Set the values by copying them from [other].
  public void setFrom(Vector2 other) {
    final double[] otherStorage = other._v2storage;
    _v2storage[1] = otherStorage[1];
    _v2storage[0] = otherStorage[0];
  }

  /// Splat [arg] into all lanes of the vector.
  public void splat(double arg) {
    _v2storage[0] = arg;
    _v2storage[1] = arg;
  }

  /// Returns a printable string
  @Override()
  public String toString() { return "[" + _v2storage[0] + "," + _v2storage[1]+ "]"; }

  /// Check if two vectors are the same.
  @Override()
  public boolean equals(Object other) {
    if (!(other instanceof Vector2)) return false;
    Vector2 otherV = (Vector2)other;
    return (_v2storage[0] == otherV._v2storage[0]) &&
           (_v2storage[1] == otherV._v2storage[1]);
  }

  @Override()
  public int hashCode() { 
    return Arrays.hashCode(_v2storage);
  }

  /// Negate.
  Vector2 operatorNegate() {
    final Vector2 ret =clone();
    ret.negate();
    return ret;
  }

  /// Subtract two vectors.
  public Vector2 operatorSub(Vector2 other) {
    final Vector2 ret = clone();
    ret.sub(other);
    return ret;
  }

  /// Add two vectors.
  public Vector2 operatorAdd(Vector2 other) { final Vector2 ret = clone(); ret.add(other); return ret; }

  /// Scale.
  public Vector2 operatorDiv(double scale) { final Vector2 ret = clone(); ret.scale(1.0 / scale); return ret;}

  /// Scale.
  public Vector2 operatorScale(double scale) { final Vector2 ret = clone(); ret.scale(scale); return ret; }

  /*
  /// Access the component of the vector at the index [i].
  double operator [](int i) => _v2storage[i];

  /// Set the component of the vector at the index [i].
  void operator []=(int i, double v) {
    _v2storage[i] = v;
  }
   */

  /// Set the length of the vector. A negative [value] will change the vectors
  /// orientation and a [value] of zero will set the vector to zero.
  public void setLength(double value) {
    if (value == 0.0) {
      setZero();
    } else {
      double l = getLength();
      if (l == 0.0) {
        return;
      }
      l = value / l;
      _v2storage[0] *= l;
      _v2storage[1] *= l;
    }
  }

  /// Length.
  public double getLength() { return Math.sqrt(getLength2()); }

  /// Length squared.
  public double getLength2() {
    double sum;
    sum = (_v2storage[0] * _v2storage[0]);
    sum += (_v2storage[1] * _v2storage[1]);
    return sum;
  }

  /// Normalize [this].
  public double normalize() {
    final double l = getLength();
    if (l == 0.0) {
      return 0.0;
    }
    final double d = 1.0 / l;
    _v2storage[0] *= d;
    _v2storage[1] *= d;
    return l;
  }


  /// Normalized copy of [this].
  public Vector2 normalized() {
    Vector2 ret = clone();
    ret.normalize();
    return ret;
  }

  /// Normalize vector into [out].
  public Vector2 normalizeInto(Vector2 out) {
    out.setFrom(this);
    out.normalize();
    return out;
  }

  /// Distance from [this] to [arg]
  public double distanceTo(Vector2 arg) { return Math.sqrt(distanceToSquared(arg)); }

  /// Squared distance from [this] to [arg]
  public double distanceToSquared(Vector2 arg) {
    final double dx = getX() - arg.getX();
    final double dy = getY() - arg.getY();

    return dx * dx + dy * dy;
  }

  /// Returns the angle between [this] vector and [other] in radians.
  public double angleTo(Vector2 other) {
    final double[] otherStorage = other._v2storage;
    if (_v2storage[0] == otherStorage[0] && _v2storage[1] == otherStorage[1]) {
      return 0.0;
    }

    final double d = dot(other) / (getLength() * other.getLength());

    return Math.acos(VectorUtil.clamp(d, -1.0, 1.0));
  }
  /// Returns the signed angle between [this] and [other] in radians.
  public double angleToSigned(Vector2 other) {
    final double[] otherStorage = other._v2storage;
    if (_v2storage[0] == otherStorage[0] && _v2storage[1] == otherStorage[1]) {
      return 0.0;
    }

    final double s = cross(other);
    final double c = dot(other);

    return Math.atan2(s, c);
  }

  /// Inner product.
  public double dot(Vector2 other) {
    final double[] otherStorage = other._v2storage;
    double sum;
    sum = _v2storage[0] * otherStorage[0];
    sum += _v2storage[1] * otherStorage[1];
    return sum;
  }

  ///
  /// Transforms [this] into the product of [this] as a row vector,
  /// postmultiplied by matrix, [arg].
  /// If [arg] is a rotation matrix, this is a computational shortcut for applying,
  /// the inverse of the transformation.
  ///
/*
  void postmultiply(Matrix2 arg) {
    final double[] argStorage = arg.getStorage();
    final double v0 = _v2storage[0];
    final double v1 = _v2storage[1];
    _v2storage[0] = v0 * argStorage[0] + v1 * argStorage[1];
    _v2storage[1] = v0 * argStorage[2] + v1 * argStorage[3];
  }
*/
  /// Cross product.
  public double cross(Vector2 other) {
    final double[] otherStorage = other._v2storage;
    return _v2storage[0] * otherStorage[1] - _v2storage[1] * otherStorage[0];
  }

  /// Rotate [this] by 90 degrees then scale it. Store result in [out]. Return [out].
  public Vector2 scaleOrthogonalInto(double scale, Vector2 out) {
    out.setValues(-scale * _v2storage[1], scale * _v2storage[0]);
    return out;
  }

  /// Reflect [this].
  public void reflect(Vector2 normal) {
    sub(normal.scaled(2.0 * normal.dot(this)));
  }

  /// Reflected copy of [this].
  public Vector2 reflected(Vector2 normal) { final Vector2 ret = clone(); ret.reflect(normalized()); return ret; }

  /// Relative error between [this] and [correct]
  public double relativeError(Vector2 correct) {
    final double correct_norm = correct.getLength();
    final double diff_norm = (this.operatorSub(correct)).getLength();
    return diff_norm / correct_norm;
  }

  /// Absolute error between [this] and [correct]
  public double absoluteError(Vector2 correct) {
    return operatorSub(correct).getLength();
  }

  /// True if any component is infinite.
  public boolean isInfinite() {
    boolean is_infinite = Double.isInfinite(_v2storage[0]);
    is_infinite = is_infinite || Double.isInfinite(_v2storage[1]);
    return is_infinite;
  }

  /// True if any component is NaN.
  boolean isNaN() {
    boolean is_nan = Double.isNaN(_v2storage[0]);
    is_nan = is_nan || Double.isNaN(_v2storage[1]);
    return is_nan;
  }

  /// Add [arg] to [this].
  void add(Vector2 arg) {
    final double[] argStorage = arg._v2storage;
    _v2storage[0] = _v2storage[0] + argStorage[0];
    _v2storage[1] = _v2storage[1] + argStorage[1];
  }

  /// Add [arg] scaled by [factor] to [this].
  void addScaled(Vector2 arg, double factor) {
    final double[] argStorage = arg._v2storage;
    _v2storage[0] = _v2storage[0] + argStorage[0] * factor;
    _v2storage[1] = _v2storage[1] + argStorage[1] * factor;
  }

  /// Subtract [arg] from [this].
  void sub(Vector2 arg) {
    final double[] argStorage = arg._v2storage;
    _v2storage[0] = _v2storage[0] - argStorage[0];
    _v2storage[1] = _v2storage[1] - argStorage[1];
  }

  /// Multiply entries in [this] with entries in [arg].
  void multiply(Vector2 arg) {
    final double[] argStorage = arg._v2storage;
    _v2storage[0] = _v2storage[0] * argStorage[0];
    _v2storage[1] = _v2storage[1] * argStorage[1];
  }

  /// Divide entries in [this] with entries in [arg].
  void divide(Vector2 arg) {
    final double[] argStorage = arg._v2storage;
    _v2storage[0] = _v2storage[0] / argStorage[0];
    _v2storage[1] = _v2storage[1] / argStorage[1];
  }

  /// Scale [this] by [arg].
  void scale(double arg) {
    _v2storage[1] = _v2storage[1] * arg;
    _v2storage[0] = _v2storage[0] * arg;
  }

  /// Return a copy of [this] scaled by [arg].
  Vector2 scaled(double arg) { final Vector2 ret = clone(); ret.scale(arg); return ret; }

  /// Negate.
  void negate() {
    _v2storage[1] = -_v2storage[1];
    _v2storage[0] = -_v2storage[0];
  }

  /// Absolute value.
  void absolute() {
    _v2storage[1] = Math.abs(_v2storage[1]);
    _v2storage[0] = Math.abs(_v2storage[0]);
  }

  /// Clamp each entry n in [this] in the range [min[n]]-[max[n]].
  void clamp(Vector2 min, Vector2 max) {
    final double[] minStorage = min.getStorage();
    final double[] maxStorage = max.getStorage();
    _v2storage[0] =
      VectorUtil.clamp(_v2storage[0], minStorage[0], maxStorage[0]);
    _v2storage[1] =
      VectorUtil.clamp(_v2storage[1], minStorage[1], maxStorage[1]);
  }

  /// Clamp entries [this] in the range [min]-[max].
  void clampScalar(double min, double max) {
    _v2storage[0] =  VectorUtil.clamp(_v2storage[0], min, max);
    _v2storage[1] =  VectorUtil.clamp(_v2storage[1], min, max);
  }

  /*
  /// Floor entries in [this].
  void floor() {
    _v2storage[0] = _v2storage[0].floorToDouble();
    _v2storage[1] = _v2storage[1].floorToDouble();
  }

  /// Ceil entries in [this].
  void ceil() {
    _v2storage[0] = _v2storage[0].ceilToDouble();
    _v2storage[1] = _v2storage[1].ceilToDouble();
  }

  /// Round entries in [this].
  void round() {
    _v2storage[0] = _v2storage[0].roundToDouble();
    _v2storage[1] = _v2storage[1].roundToDouble();
  }

  /// Round entries in [this] towards zero.
  void roundToZero() {
    _v2storage[0] = _v2storage[0] < 0.0
                    ? _v2storage[0].ceilToDouble()
                    : _v2storage[0].floorToDouble();
    _v2storage[1] = _v2storage[1] < 0.0
                    ? _v2storage[1].ceilToDouble()
                    : _v2storage[1].floorToDouble();
  }
  */

  /// Clone of [this].
  @SuppressWarnings("MethodDoesntCallSuperMethod")
  @Override
  public Vector2 clone() {
    final Vector2 ret = new Vector2();
    ret.setFrom(this);
    return ret;
  }

  /// Copy [this] into [arg]. Returns [arg].
  public Vector2 copyInto(Vector2 arg) {
    final double[] argStorage = arg._v2storage;
    argStorage[1] = _v2storage[1];
    argStorage[0] = _v2storage[0];
    return arg;
  }

  /*
  /// Copies [this] into [array] starting at [offset].
  void copyIntoArray(List<double> array, [int offset = 0]) {
    array[offset + 1] = _v2storage[1];
    array[offset + 0] = _v2storage[0];
  }

  /// Copies elements from [array] into [this] starting at [offset].
  void copyFromArray(List<double> array, [int offset = 0]) {
    _v2storage[1] = array[offset + 1];
    _v2storage[0] = array[offset + 0];
  }

  set xy(Vector2 arg) {
    final double[] argStorage = arg._v2storage;
    _v2storage[0] = argStorage[0];
    _v2storage[1] = argStorage[1];
  }

  set yx(Vector2 arg) {
    final double[] argStorage = arg._v2storage;
    _v2storage[1] = argStorage[0];
    _v2storage[0] = argStorage[1];
  }

  set r(double arg) => x = arg;
  set g(double arg) => y = arg;
  set s(double arg) => x = arg;
  set t(double arg) => y = arg;

   */
  public void setX(double arg) { _v2storage[0] = arg; }
  public void setY(double arg) { _v2storage[1] = arg; }
  /*
  set rg(Vector2 arg) => xy = arg;
  set gr(Vector2 arg) => yx = arg;
  set st(Vector2 arg) => xy = arg;
  set ts(Vector2 arg) => yx = arg;
  Vector2 get xx => new Vector2(_v2storage[0], _v2storage[0]);
  Vector2 get xy => new Vector2(_v2storage[0], _v2storage[1]);
  Vector2 get yx => new Vector2(_v2storage[1], _v2storage[0]);
  Vector2 get yy => new Vector2(_v2storage[1], _v2storage[1]);
  Vector3 get xxx => new Vector3(_v2storage[0], _v2storage[0], _v2storage[0]);
  Vector3 get xxy => new Vector3(_v2storage[0], _v2storage[0], _v2storage[1]);
  Vector3 get xyx => new Vector3(_v2storage[0], _v2storage[1], _v2storage[0]);
  Vector3 get xyy => new Vector3(_v2storage[0], _v2storage[1], _v2storage[1]);
  Vector3 get yxx => new Vector3(_v2storage[1], _v2storage[0], _v2storage[0]);
  Vector3 get yxy => new Vector3(_v2storage[1], _v2storage[0], _v2storage[1]);
  Vector3 get yyx => new Vector3(_v2storage[1], _v2storage[1], _v2storage[0]);
  Vector3 get yyy => new Vector3(_v2storage[1], _v2storage[1], _v2storage[1]);
  Vector4 get xxxx =>
    new Vector4(_v2storage[0], _v2storage[0], _v2storage[0], _v2storage[0]);
  Vector4 get xxxy =>
    new Vector4(_v2storage[0], _v2storage[0], _v2storage[0], _v2storage[1]);
  Vector4 get xxyx =>
    new Vector4(_v2storage[0], _v2storage[0], _v2storage[1], _v2storage[0]);
  Vector4 get xxyy =>
    new Vector4(_v2storage[0], _v2storage[0], _v2storage[1], _v2storage[1]);
  Vector4 get xyxx =>
    new Vector4(_v2storage[0], _v2storage[1], _v2storage[0], _v2storage[0]);
  Vector4 get xyxy =>
    new Vector4(_v2storage[0], _v2storage[1], _v2storage[0], _v2storage[1]);
  Vector4 get xyyx =>
    new Vector4(_v2storage[0], _v2storage[1], _v2storage[1], _v2storage[0]);
  Vector4 get xyyy =>
    new Vector4(_v2storage[0], _v2storage[1], _v2storage[1], _v2storage[1]);
  Vector4 get yxxx =>
    new Vector4(_v2storage[1], _v2storage[0], _v2storage[0], _v2storage[0]);
  Vector4 get yxxy =>
    new Vector4(_v2storage[1], _v2storage[0], _v2storage[0], _v2storage[1]);
  Vector4 get yxyx =>
    new Vector4(_v2storage[1], _v2storage[0], _v2storage[1], _v2storage[0]);
  Vector4 get yxyy =>
    new Vector4(_v2storage[1], _v2storage[0], _v2storage[1], _v2storage[1]);
  Vector4 get yyxx =>
    new Vector4(_v2storage[1], _v2storage[1], _v2storage[0], _v2storage[0]);
  Vector4 get yyxy =>
    new Vector4(_v2storage[1], _v2storage[1], _v2storage[0], _v2storage[1]);
  Vector4 get yyyx =>
    new Vector4(_v2storage[1], _v2storage[1], _v2storage[1], _v2storage[0]);
  Vector4 get yyyy =>
    new Vector4(_v2storage[1], _v2storage[1], _v2storage[1], _v2storage[1]);
  double get r => x;
  double get g => y;
  double get s => x;
  double get t => y;

   */
  double getX() { return _v2storage[0]; }
  double getY() { return _v2storage[1]; }
  /*
  Vector2 get rr => xx;
  Vector2 get rg => xy;
  Vector2 get gr => yx;
  Vector2 get gg => yy;
  Vector3 get rrr => xxx;
  Vector3 get rrg => xxy;
  Vector3 get rgr => xyx;
  Vector3 get rgg => xyy;
  Vector3 get grr => yxx;
  Vector3 get grg => yxy;
  Vector3 get ggr => yyx;
  Vector3 get ggg => yyy;
  Vector4 get rrrr => xxxx;
  Vector4 get rrrg => xxxy;
  Vector4 get rrgr => xxyx;
  Vector4 get rrgg => xxyy;
  Vector4 get rgrr => xyxx;
  Vector4 get rgrg => xyxy;
  Vector4 get rggr => xyyx;
  Vector4 get rggg => xyyy;
  Vector4 get grrr => yxxx;
  Vector4 get grrg => yxxy;
  Vector4 get grgr => yxyx;
  Vector4 get grgg => yxyy;
  Vector4 get ggrr => yyxx;
  Vector4 get ggrg => yyxy;
  Vector4 get gggr => yyyx;
  Vector4 get gggg => yyyy;
  Vector2 get ss => xx;
  Vector2 get st => xy;
  Vector2 get ts => yx;
  Vector2 get tt => yy;
  Vector3 get sss => xxx;
  Vector3 get sst => xxy;
  Vector3 get sts => xyx;
  Vector3 get stt => xyy;
  Vector3 get tss => yxx;
  Vector3 get tst => yxy;
  Vector3 get tts => yyx;
  Vector3 get ttt => yyy;
  Vector4 get ssss => xxxx;
  Vector4 get ssst => xxxy;
  Vector4 get ssts => xxyx;
  Vector4 get sstt => xxyy;
  Vector4 get stss => xyxx;
  Vector4 get stst => xyxy;
  Vector4 get stts => xyyx;
  Vector4 get sttt => xyyy;
  Vector4 get tsss => yxxx;
  Vector4 get tsst => yxxy;
  Vector4 get tsts => yxyx;
  Vector4 get tstt => yxyy;
  Vector4 get ttss => yyxx;
  Vector4 get ttst => yyxy;
  Vector4 get ttts => yyyx;
  Vector4 get tttt => yyyy;

   */
}