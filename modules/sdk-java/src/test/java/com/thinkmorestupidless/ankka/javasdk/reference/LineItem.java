package com.thinkmorestupidless.ankka.javasdk.reference;

import com.thinkmorestupidless.ankka.javasdk.annotations.Describe;
import com.thinkmorestupidless.ankka.javasdk.codec.Manifest;

/** One line of a cart, field for field the Scala reference's. */
@Manifest("line-item")
public record LineItem(
    @Describe("The product's id") String productId,
    @Describe("The product's name") String name,
    @Describe("How many; greater than zero") int quantity) {}
