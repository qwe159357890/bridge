from setuptools import setup, find_packages

setup(
    name="bridge-rpc",
    version="0.1.0",
    description="Python client for Bridge phone automation RPC",
    author="Bridge",
    packages=find_packages(),
    install_requires=["requests>=2.25"],
    python_requires=">=3.7",
    classifiers=[
        "Programming Language :: Python :: 3",
        "License :: OSI Approved :: MIT License",
    ],
)
