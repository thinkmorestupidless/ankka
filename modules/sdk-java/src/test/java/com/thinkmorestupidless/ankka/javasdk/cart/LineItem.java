package com.thinkmorestupidless.ankka.javasdk.cart;

import com.thinkmorestupidless.ankka.javasdk.codec.Manifest;

@Manifest("line-item")
public record LineItem(String productId, String name, int quantity) {}
