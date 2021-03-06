package org.apache.mesos.util;

import org.junit.Assert;
import org.junit.Test;

import javax.validation.ConstraintViolationException;

/**
 * Test for WriteOnceLinkedHashMap.
 */
public class WriteOnceLinkedHashMapTest {
    @Test
    public void valid() {
        WriteOnceLinkedHashMap<String, String> map = new WriteOnceLinkedHashMap<>();
        map.put("a", "b");
        map.put("c", "d");
        Assert.assertEquals(2, map.entrySet().size());
    }

    @Test(expected = ConstraintViolationException.class)
    public void inValid() {
        WriteOnceLinkedHashMap<String, String> map = new WriteOnceLinkedHashMap<>();
        map.put("a", "b");
        map.put("a", "d");
    }
}
