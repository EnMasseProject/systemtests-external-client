package io.enmasse.test;

public class Address {

	private String address;
	private AddressType addressType;

	public Address(String address, AddressType addressType) {
		this.address = address;
		this.addressType = addressType;
	}

	public String getAddress() {
		return address;
	}

	public AddressType getAddressType() {
		return addressType;
	}

}
