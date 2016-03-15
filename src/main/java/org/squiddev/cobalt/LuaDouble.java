/*
 * ****************************************************************************
 * Original Source: Copyright (c) 2009-2011 Luaj.org. All rights reserved.
 * Modifications: Copyright (c) 2015-2016 SquidDev
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 * ****************************************************************************
 */
package org.squiddev.cobalt;

/**
 * Extension of {@link LuaNumber} which can hold a Java double as its value.
 *
 * These instance are not instantiated directly by clients, but indirectly
 * via the static functions {@link ValueFactory#valueOf(int)} or {@link ValueFactory#valueOf(double)}
 * functions.  This ensures that values which can be represented as int
 * are wrapped in {@link LuaInteger} instead of {@link LuaDouble}.
 *
 * Almost all API's implemented in LuaDouble are defined and documented in {@link LuaValue}.
 *
 * However the constants {@link #NAN}, {@link #POSINF}, {@link #NEGINF},
 * {@link #JSTR_NAN}, {@link #JSTR_POSINF}, and {@link #JSTR_NEGINF} may be useful
 * when dealing with Nan or Infinite values.
 *
 * @see LuaValue
 * @see LuaNumber
 * @see LuaInteger
 * @see ValueFactory#valueOf(int)
 * @see ValueFactory#valueOf(double)
 */
public class LuaDouble extends LuaNumber {

	/**
	 * Constant LuaDouble representing NaN (not a number)
	 */
	public static final LuaDouble NAN = new LuaDouble(Double.NaN);

	/**
	 * Constant LuaDouble representing positive infinity
	 */
	public static final LuaDouble POSINF = new LuaDouble(Double.POSITIVE_INFINITY);

	/**
	 * Constant LuaDouble representing negative infinity
	 */
	public static final LuaDouble NEGINF = new LuaDouble(Double.NEGATIVE_INFINITY);

	/**
	 * Constant String representation for NaN (not a number), "nan"
	 */
	public static final String JSTR_NAN = "nan";

	/**
	 * Constant String representation for positive infinity, "inf"
	 */
	public static final String JSTR_POSINF = "inf";

	/**
	 * Constant String representation for negative infinity, "-inf"
	 */
	public static final String JSTR_NEGINF = "-inf";

	/**
	 * The value being held by this instance.
	 */
	final double v;

	public static LuaNumber valueOf(double d) {
		int id = (int) d;
		return d == id ? LuaInteger.valueOf(id) : new LuaDouble(d);
	}

	/**
	 * Don't allow ints to be boxed by DoubleValues
	 */
	private LuaDouble(double d) {
		this.v = d;
	}

	public int hashCode() {
		long l = Double.doubleToLongBits(v);
		return ((int) (l >> 32)) | (int) l;
	}

	@Override
	public boolean isLong() {
		return v == (long) v;
	}

	@Override
	public double toDouble() {
		return v;
	}

	@Override
	public int toInteger() {
		return (int) (long) v;
	}

	@Override
	public long toLong() {
		return (long) v;
	}

	@Override
	public double optDouble(double defval) {
		return v;
	}

	@Override
	public int optInteger(int defval) {
		return (int) (long) v;
	}

	@Override
	public LuaInteger optLuaInteger(LuaInteger defval) {
		return LuaInteger.valueOf((int) (long) v);
	}

	@Override
	public long optLong(long defval) {
		return (long) v;
	}

	@Override
	public LuaInteger checkLuaInteger() {
		return LuaInteger.valueOf((int) (long) v);
	}

	// object equality, used for key comparison
	public boolean equals(Object o) {
		return o instanceof LuaDouble && ((LuaDouble) o).v == v;
	}

	@Override
	public boolean raweq(LuaValue val) {
		return val.raweq(v);
	}

	@Override
	public boolean raweq(double val) {
		return v == val;
	}

	@Override
	public boolean raweq(int val) {
		return v == val;
	}

	// string comparison
	@Override
	public int strcmp(LuaString rhs) {
		throw ErrorFactory.typeError(this, "attempt to compare number with string");
	}

	@Override
	public String toString() {
		/*
		if ( v == 0.0 ) { // never occurs in J2me
			long bits = Double.doubleToLongBits( v );
			return ( bits >> 63 == 0 ) ? "0" : "-0";
		}
		*/
		long l = (long) v;
		if (l == v) {
			return Long.toString(l);
		}
		if (Double.isNaN(v)) {
			return JSTR_NAN;
		}
		if (Double.isInfinite(v)) {
			return (v < 0 ? JSTR_NEGINF : JSTR_POSINF);
		}
		return Float.toString((float) v);
	}

	@Override
	public LuaString strvalue() {
		return LuaString.valueOf(toString());
	}

	@Override
	public LuaString optLuaString(LuaString defval) {
		return LuaString.valueOf(toString());
	}

	@Override
	public LuaValue toLuaString() {
		return LuaString.valueOf(toString());
	}

	@Override
	public String optString(String defval) {
		return toString();
	}

	@Override
	public int checkInteger() {
		return (int) (long) v;
	}

	@Override
	public long checkLong() {
		return (long) v;
	}

	@Override
	public double checkDouble() {
		return v;
	}

	@Override
	public String checkString() {
		return toString();
	}

	@Override
	public LuaString checkLuaString() {
		return LuaString.valueOf(toString());
	}

	@Override
	public LuaValue checkValidKey() {
		if (Double.isNaN(v)) {
			throw new LuaError("table index expected, got nan");
		}
		return this;
	}

	@Override
	public double checkArith() {
		return v;
	}
}