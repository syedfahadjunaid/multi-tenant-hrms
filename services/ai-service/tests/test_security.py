from cryptography.hazmat.primitives.asymmetric import rsa

from tests.conftest import auth

ASK = {"question": "How many days of annual leave do I get?"}


def test_missing_token_is_rejected(client):
    response = client.post("/v1/ask", json=ASK)
    assert response.status_code == 401
    assert response.headers["WWW-Authenticate"] == "Bearer"


def test_valid_token_is_accepted(client, make_token):
    response = client.post("/v1/ask", json=ASK, headers=auth(make_token()))
    assert response.status_code == 200


def test_token_signed_by_another_key_is_rejected(client, make_token):
    attacker_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    response = client.post("/v1/ask", json=ASK, headers=auth(make_token(key=attacker_key)))
    assert response.status_code == 401


def test_expired_token_is_rejected(client, make_token):
    response = client.post("/v1/ask", json=ASK, headers=auth(make_token(expires_in=-120)))
    assert response.status_code == 401


def test_token_for_another_audience_is_rejected(client, make_token):
    response = client.post("/v1/ask", json=ASK, headers=auth(make_token(audience="other-app")))
    assert response.status_code == 401


def test_token_from_another_issuer_is_rejected(client, make_token):
    response = client.post("/v1/ask", json=ASK, headers=auth(make_token(issuer="http://evil")))
    assert response.status_code == 401


def test_token_without_tenant_is_rejected(client, make_token):
    response = client.post("/v1/ask", json=ASK, headers=auth(make_token(tenant=None)))
    assert response.status_code == 401


def test_only_hr_admins_can_ingest_documents(client, make_token):
    doc = {"title": "Leave policy", "content": "Employees get 25 days of annual leave."}

    as_employee = client.post("/v1/documents", json=doc, headers=auth(make_token()))
    as_admin = client.post("/v1/documents", json=doc, headers=auth(make_token(roles=("HR_ADMIN",))))

    assert as_employee.status_code == 403
    assert as_admin.status_code == 201
    assert as_admin.json()["chunks"] == 1


def test_client_cannot_choose_its_tenant(client, make_token):
    doc = {"title": "x", "content": "y", "tenantId": "globex"}
    response = client.post("/v1/documents", json=doc, headers=auth(make_token(roles=("HR_ADMIN",))))
    assert response.status_code == 422  # unknown field rejected
