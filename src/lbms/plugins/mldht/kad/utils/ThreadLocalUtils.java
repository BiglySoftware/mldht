/*******************************************************************************
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 ******************************************************************************/
package lbms.plugins.mldht.kad.utils;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Objects;
import java.util.Random;

import the8472.bencode.BDecoder;

public class ThreadLocalUtils {

	private static ThreadLocal<Random> randTL = withInitial(() -> {
		try
		{
			return SecureRandom.getInstance("SHA1PRNG");
		} catch (NoSuchAlgorithmException e) {
			return new SecureRandom();
		}
	});
	
	private static ThreadLocal<MessageDigest> sha1TL = withInitial(() -> {
		try {
			return MessageDigest.getInstance("SHA");
		} catch (NoSuchAlgorithmException e) {
			throw new Error("expected SHA1 digest to be available", e);
		}
	});
	
	private static ThreadLocal<BDecoder> decoder = withInitial(() -> new BDecoder());
	

	public static Random getThreadLocalRandom() {
		return randTL.get();
	}
	
	public static BDecoder getDecoder() {
		return decoder.get();
	}
	
	public static MessageDigest getThreadLocalSHA1() {
		return sha1TL.get();
	}

	public static <S> ThreadLocal<S> withInitial(Supplier<? extends S> var0) {
		return new SuppliedThreadLocal(var0);
	}

	static final class SuppliedThreadLocal<T> extends ThreadLocal<T> {
		private final Supplier<? extends T> supplier;

		SuppliedThreadLocal(Supplier<? extends T> var1) {
			this.supplier = (Supplier) Objects.requireNonNull(var1);
		}

		protected T initialValue() {
			return this.supplier.get();
		}
	}

	@FunctionalInterface
	public interface Supplier<T> {
		T get();
	}
}
