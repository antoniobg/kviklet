FROM python:3.12-alpine

WORKDIR /srv
COPY app.py .

# Standard library only, so there is nothing to install.
EXPOSE 8000
CMD ["python", "-u", "app.py"]
