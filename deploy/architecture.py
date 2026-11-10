"""deploy/architecture.png 생성 스크립트 (mingrammer/diagrams).

구성이 바뀌면 이 파일을 고치고 다시 그린다:
    brew install graphviz
    python3 -m venv /tmp/diagrams-venv && /tmp/diagrams-venv/bin/pip install diagrams
    cd deploy && /tmp/diagrams-venv/bin/python architecture.py

🔴 AWS 계정 ID·Elastic IP·도메인 같은 실제 값은 그림에 넣지 않는다 — 공개 레포다.
"""
from diagrams import Cluster, Diagram, Edge
from diagrams.aws.compute import EC2ElasticIpAddress, ECR
from diagrams.aws.storage import EBS
from diagrams.aws.general import MobileClient
from diagrams.onprem.certificates import LetsEncrypt
from diagrams.onprem.client import Client
from diagrams.onprem.database import PostgreSQL
from diagrams.onprem.inmemory import Redis
from diagrams.onprem.network import Internet, Nginx
from diagrams.programming.framework import Spring

FONT = "Apple SD Gothic Neo"  # 한글 라벨용

graph_attr = {"fontname": FONT, "fontsize": "22", "pad": "0.6", "nodesep": "0.7", "ranksep": "0.9", "splines": "spline"}
node_attr = {"fontname": FONT, "fontsize": "13"}
edge_attr = {"fontname": FONT, "fontsize": "11"}

with Diagram(
    "challenge-server 운영 구성",
    filename="architecture",
    outformat="png",
    show=False,
    direction="LR",
    graph_attr=graph_attr,
    node_attr=node_attr,
    edge_attr=edge_attr,
):
    app_user = MobileClient("모바일 앱")
    dns = Internet("가비아 DNS\nA 레코드 api.<도메인>")
    dev = Client("개발 Mac\nscripts/deploy.sh")

    with Cluster("AWS  ap-southeast-2 (시드니)", graph_attr={"fontname": FONT, "fontsize": "16"}):
        eip = EC2ElasticIpAddress("Elastic IP\n(고정 공인 IP)")
        ecr = ECR("ECR\nchallenge-server\n(latest, <sha> · 최근 10개)")

        with Cluster("보안그룹  22: 관리자 IP / 80·443: 전체", graph_attr={"fontname": FONT, "fontsize": "14", "bgcolor": "#FFF5F5"}):
            with Cluster("EC2  t4g.small · arm64 · AL2023 · RAM 2GB + swap 2GB", graph_attr={"fontname": FONT, "fontsize": "14"}):
                with Cluster("docker compose  /srv/challenge", graph_attr={"fontname": FONT, "fontsize": "13", "bgcolor": "#EAF4FF"}):
                    nginx = Nginx("nginx\n:80 → 301 https\n:443 TLS · 6MB 제한")
                    certbot = LetsEncrypt("certbot\n12h 마다 갱신")
                    app = Spring("app (Spring Boot)\nprod · :8080 내부 전용\nmem 1GB")
                    db = PostgreSQL("postgres 16\nmem 512MB")
                    cache = Redis("redis 7\nmem 128MB")

                disk = EBS("EBS 30GB gp3\n(암호화)\nDB · Redis · 사진\n.env · 인증서")

    app_user >> Edge(label="HTTPS") >> dns >> eip >> nginx
    nginx >> Edge(label="proxy_pass") >> app
    app >> db
    app >> cache
    certbot >> Edge(style="dashed", label="인증서") >> nginx
    db >> Edge(style="dotted", color="gray") >> disk
    cache >> Edge(style="dotted", color="gray") >> disk

    dev >> Edge(label="docker push", color="darkgreen") >> ecr
    ecr >> Edge(label="docker pull\n(EC2 IAM Role 인증)", color="darkgreen", style="dashed") >> app
