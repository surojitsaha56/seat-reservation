BASE_URL ?= http://localhost:8080

.PHONY: burst
burst:
	./burst.sh $(BASE_URL)
