/*******************************************************************************
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 ******************************************************************************/
package the8472.utils;

/* Android minSDK 26
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
*/
import java.lang.reflect.Method;

public class Arrays {
	
	private static final Method compareImpl;
	private static final Method mismatchImpl;
	
	static {

		Method compU = null;
		Method mism = null;
		
		try {
			compU = java.util.Arrays.class.getDeclaredMethod("compareUnsigned", byte[].class, byte[].class);
			mism = java.util.Arrays.class.getDeclaredMethod("mismatch", byte[].class, byte[].class);
/* Android minSDK 26
			compU = MethodHandles.lookup().findStatic(java.util.Arrays.class, "compareUnsigned", MethodType.methodType(int.class, byte[].class, byte[].class));
			mism = MethodHandles.lookup().findStatic(java.util.Arrays.class, "mismatch", MethodType.methodType(int.class, byte[].class, byte[].class));
		} catch (NoSuchMethodException | IllegalAccessException e) {
*/
		} catch (Throwable ignore) {
			// no java9
		}
		
		if(compU == null) {
			try {
				compU = Arrays.class.getDeclaredMethod("compareUnsignedFallback", byte[].class,
						byte[].class);
				mism = Arrays.class.getDeclaredMethod("mismatchFallback", byte[].class, byte[].class);
/* Android minSDK 26
				compU = MethodHandles.lookup().findStatic(Arrays.class, "compareUnsignedFallback", MethodType.methodType(int.class, byte[].class, byte[].class));
				mism  = MethodHandles.lookup().findStatic(Arrays.class, "mismatchFallback", MethodType.methodType(int.class, byte[].class, byte[].class));
				
			}  catch (NoSuchMethodException | IllegalAccessException e) {
			
 */
			} catch (Throwable nogood) {
				throw new Error("should not happen");
			}
		}
		
		
		compareImpl = compU;
		mismatchImpl = mism;
	}
	
	public static int compareUnsigned(byte[] a, byte[] b) {
		try {
			return (Integer) compareImpl.invoke(null, a, b);
		} catch(RuntimeException e) {
			throw e;
		} catch(Throwable e) {
			throw new Error("should not happen", e);
		}
		
	}
	
	public static int mismatch(byte[] a, byte[] b) {
		try {
			return (Integer) mismatchImpl.invoke(null, a, b);
		} catch(RuntimeException e) {
			throw e;
		} catch(Throwable e) {
			throw new Error("should not happen", e);
		}
		
	}
	
	
	
	@SuppressWarnings("unused")
	private static int compareUnsignedFallback(byte[] a, byte[] b) {
		int minLength = Math.min(a.length, b.length);
		for(int i=0;i+7<minLength;i+=8)
		{
			long la = Byte.toUnsignedLong(a[i]) << 56 |
					Byte.toUnsignedLong(a[i+1]) << 48 |
					Byte.toUnsignedLong(a[i+2]) << 40 |
					Byte.toUnsignedLong(a[i+3]) << 32 |
					Byte.toUnsignedLong(a[i+4]) << 24 |
					Byte.toUnsignedLong(a[i+5]) << 16 |
					Byte.toUnsignedLong(a[i+6]) << 8 |
					Byte.toUnsignedLong(a[i+7]) << 0;
			long lb = Byte.toUnsignedLong(b[i]) << 56 |
					Byte.toUnsignedLong(b[i+1]) << 48 |
					Byte.toUnsignedLong(b[i+2]) << 40 |
					Byte.toUnsignedLong(b[i+3]) << 32 |
					Byte.toUnsignedLong(b[i+4]) << 24 |
					Byte.toUnsignedLong(b[i+5]) << 16 |
					Byte.toUnsignedLong(b[i+6]) << 8 |
					Byte.toUnsignedLong(b[i+7]) << 0;
			
			if(la != lb)
				return Long.compareUnsigned(la, lb);
			
		}
		
		int offset = minLength - minLength & 0x7;
		
		for(int i=offset;i<minLength;i++) {
			int ia = Byte.toUnsignedInt(a[i]);
			int ib = Byte.toUnsignedInt(b[i]);
			if(ia != ib)
				return Integer.compare(ia, ib);
		}
		
		return a.length - b.length;
	}
	
	@SuppressWarnings("unused")
	private static int mismatchFallback(byte[] a, byte[] b) {
		int min = Math.min(a.length, b.length);
		for(int i=0;i<min;i++) {
			if(a[i] != b[i])
				return i;
		}
		
		return a.length == b.length ? -1 : min;
	}

}
